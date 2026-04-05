using System.Text.Json;
using Boomerang.Client.Models;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Extensions.Logging;

namespace Boomerang.Client.AspNetCore;

/// <summary>
/// Reads the raw body, verifies HMAC, and binds <see cref="BoomerangWebhookPayload"/> to the action.
/// </summary>
public sealed class BoomerangWebhookFilter : IAsyncActionFilter
{
    private readonly string _secret;
    private readonly string _payloadParameterName;
    private readonly ILogger<BoomerangWebhookFilter>? _logger;

    /// <summary>Used by <see cref="BoomerangWebhookAttribute"/> via <c>ActivatorUtilities</c>.</summary>
    public BoomerangWebhookFilter(string secret, string payloadParameterName, ILogger<BoomerangWebhookFilter>? logger = null)
    {
        _secret = secret ?? throw new ArgumentNullException(nameof(secret));
        _payloadParameterName = payloadParameterName ?? throw new ArgumentNullException(nameof(payloadParameterName));
        _logger = logger;
    }

    /// <inheritdoc />
    public async Task OnActionExecutionAsync(ActionExecutingContext context, ActionExecutionDelegate next)
    {
        var request = context.HttpContext.Request;
        request.EnableBuffering();

        await using var ms = new MemoryStream();
        await request.Body.CopyToAsync(ms, context.HttpContext.RequestAborted).ConfigureAwait(false);
        var bodyBytes = ms.ToArray();
        request.Body.Position = 0;

        var sig = request.Headers["X-Signature-SHA256"].FirstOrDefault();
        if (!BoomerangSignature.Verify(bodyBytes, sig, _secret))
        {
            _logger?.LogWarning("Boomerang webhook signature verification failed.");
            context.Result = new UnauthorizedObjectResult(new { error = "Invalid webhook signature" });
            return;
        }

        BoomerangWebhookPayload? payload;
        try
        {
            payload = JsonSerializer.Deserialize<BoomerangWebhookPayload>(bodyBytes, BoomerangJson.SerializerOptions);
        }
        catch (JsonException ex)
        {
            _logger?.LogWarning(ex, "Boomerang webhook JSON parse failed.");
            context.Result = new BadRequestObjectResult(new { error = "Invalid webhook body" });
            return;
        }

        if (payload == null)
        {
            context.Result = new BadRequestObjectResult(new { error = "Invalid webhook body" });
            return;
        }

        if (context.ActionArguments.ContainsKey(_payloadParameterName))
            context.ActionArguments[_payloadParameterName] = payload;

        await next().ConfigureAwait(false);
    }
}

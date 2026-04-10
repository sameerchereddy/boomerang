using System.Text.Json;
using Boomerang.Client.Exceptions;
using Boomerang.Client.Models;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace Boomerang.Sample.Web.Controllers;

[ApiController]
[Route("demo")]
public sealed class DemoController : ControllerBase
{
    private readonly BoomerangDemoServices _boomerang;
    private readonly BoomerangOptions _options;
    private readonly IConfiguration _configuration;
    private readonly LastWebhookStore _lastWebhook;

    public DemoController(
        BoomerangDemoServices boomerang,
        IOptions<BoomerangOptions> options,
        IConfiguration configuration,
        LastWebhookStore lastWebhook)
    {
        _boomerang = boomerang;
        _options = options.Value;
        _configuration = configuration;
        _lastWebhook = lastWebhook;
    }

    /// <summary>Whether <see cref="Boomerang.Client.BoomerangClient"/> demo endpoints can call the server.</summary>
    [HttpGet("status")]
    public ActionResult<object> Status()
    {
        var defaultCallbackUrl = CallbackUrlResolver.Resolve(null, _options);
        var webhookSecretConfigured = WebhookSecretHelper.IsConfigured(_configuration);

        if (_boomerang.IsReady)
        {
            return Ok(new
            {
                ready = true,
                baseUrl = _options.BaseUrl,
                apiPath = _options.ApiPath,
                hasStaticJwt = !string.IsNullOrWhiteSpace(_options.Jwt),
                mintingJwtFromSecret = !string.IsNullOrWhiteSpace(_options.JwtSecret),
                defaultCallbackUrl,
                webhookSecretConfigured,
            });
        }

        return Ok(new
        {
            ready = false,
            reason = _boomerang.NotReadyReason,
            defaultCallbackUrl,
            webhookSecretConfigured,
        });
    }

    /// <summary>Last webhook received at <c>POST /hooks/done</c> (in-memory; demo only).</summary>
    [HttpGet("last-webhook")]
    public ActionResult<object> LastWebhook()
    {
        var snap = _lastWebhook.GetLast();
        if (snap == null)
        {
            return Ok(new { received = false });
        }

        return Ok(new
        {
            received = true,
            receivedAtUtc = snap.ReceivedAtUtc,
            snap.JobId,
            snap.Status,
            completedAt = snap.CompletedAt,
            resultJson = snap.ResultJson,
        });
    }

    /// <summary>Enqueue a job via <see cref="Boomerang.Client.BoomerangClient.TriggerAsync"/>.</summary>
    [HttpPost("jobs")]
    public async Task<ActionResult<object>> Trigger([FromBody] DemoTriggerBody? body, CancellationToken cancellationToken)
    {
        if (!_boomerang.IsReady || _boomerang.Client == null)
            return ServiceUnavailable();

        var callbackUrl = CallbackUrlResolver.Resolve(body?.CallbackUrl, _options);
        if (callbackUrl == null)
            return BadRequest(new
            {
                error = "Set CallbackUrl in the request body or Boomerang:SamplePublicBaseUrl so the sample can build https://your-host/hooks/done.",
            });

        // Boomerang validates callbackSecret with @Size(min=32) when non-null; empty strings fail as 400.
        var callbackSecretRaw = body?.CallbackSecret
            ?? _configuration["Webhook:Secret"]
            ?? Environment.GetEnvironmentVariable("WEBHOOK_SECRET");
        var callbackSecret = string.IsNullOrWhiteSpace(callbackSecretRaw)
            ? null
            : callbackSecretRaw.Trim();
        if (callbackSecret != null && callbackSecret.Length < 32)
        {
            return BadRequest(new
            {
                error = "callbackSecret must be at least 32 characters when set (Boomerang API rule). Clear Webhook:Secret / WEBHOOK_SECRET or use a longer secret, or omit it for unsigned callbacks.",
            });
        }

        var request = new BoomerangTriggerRequest
        {
            CallbackUrl = callbackUrl,
            CallbackSecret = callbackSecret,
            IdempotencyKey = body?.IdempotencyKey,
        };

        try
        {
            var response = await _boomerang.Client.TriggerAsync(request, cancellationToken).ConfigureAwait(false);
            return StatusCode(StatusCodes.Status202Accepted, new { response.JobId, pollUrl = Url.Action(nameof(Poll), new { jobId = response.JobId }) });
        }
        catch (BoomerangApiException ex)
        {
            return FromBoomerangClient(ex);
        }
    }

    /// <summary>Poll job status via <see cref="Boomerang.Client.BoomerangClient.PollAsync"/>.</summary>
    [HttpGet("jobs/{jobId}")]
    public async Task<ActionResult<BoomerangJobStatusResponse>> Poll(string jobId, CancellationToken cancellationToken)
    {
        if (!_boomerang.IsReady || _boomerang.Client == null)
            return ServiceUnavailable();

        try
        {
            var status = await _boomerang.Client.PollAsync(jobId, cancellationToken).ConfigureAwait(false);
            return Ok(status);
        }
        catch (BoomerangApiException ex)
        {
            return FromBoomerangClient(ex);
        }
    }

    /// <summary>List failed webhooks via <see cref="Boomerang.Client.BoomerangClient.ListFailedWebhooksAsync"/>.</summary>
    [HttpGet("failed-webhooks")]
    public async Task<ActionResult<JsonElement>> ListFailedWebhooks(CancellationToken cancellationToken)
    {
        if (!_boomerang.IsReady || _boomerang.Client == null)
            return ServiceUnavailable();

        try
        {
            var json = await _boomerang.Client.ListFailedWebhooksAsync(cancellationToken).ConfigureAwait(false);
            return Ok(json);
        }
        catch (BoomerangApiException ex)
        {
            return FromBoomerangClient(ex);
        }
    }

    /// <summary>Replay a failed webhook via <see cref="Boomerang.Client.BoomerangClient.ReplayFailedWebhookAsync"/>.</summary>
    [HttpPost("failed-webhooks/{jobId}/replay")]
    public async Task<ActionResult<JsonElement>> ReplayFailedWebhook(string jobId, CancellationToken cancellationToken)
    {
        if (!_boomerang.IsReady || _boomerang.Client == null)
            return ServiceUnavailable();

        try
        {
            var json = await _boomerang.Client.ReplayFailedWebhookAsync(jobId, cancellationToken).ConfigureAwait(false);
            return Ok(json);
        }
        catch (BoomerangApiException ex)
        {
            return FromBoomerangClient(ex);
        }
    }

    /// <summary>Deletes a failed-webhooks entry via <see cref="Boomerang.Client.BoomerangClient.DeleteFailedWebhookAsync"/>.</summary>
    [HttpDelete("failed-webhooks/{jobId}")]
    public async Task<IActionResult> DeleteFailedWebhook(string jobId, CancellationToken cancellationToken)
    {
        if (!_boomerang.IsReady || _boomerang.Client == null)
            return ServiceUnavailable();

        try
        {
            await _boomerang.Client.DeleteFailedWebhookAsync(jobId, cancellationToken).ConfigureAwait(false);
            return NoContent();
        }
        catch (BoomerangApiException ex)
        {
            return FromBoomerangClient(ex);
        }
    }

    private ObjectResult ServiceUnavailable() =>
        StatusCode(StatusCodes.Status503ServiceUnavailable, new { error = _boomerang.NotReadyReason });

    /// <summary>Maps Boomerang HTTP errors to JSON for the dashboard / API callers.</summary>
    private ObjectResult FromBoomerangClient(BoomerangApiException ex)
    {
        if (ex is BoomerangConflictException c)
        {
            return StatusCode((int)ex.StatusCode, new
            {
                error = ex.Message,
                boomerangResponseBody = ex.ResponseBody,
                retryAfterSeconds = c.RetryAfterSeconds,
            });
        }

        return StatusCode((int)ex.StatusCode, new
        {
            error = ex.Message,
            boomerangResponseBody = ex.ResponseBody,
        });
    }
}

/// <summary>Optional JSON body for <c>POST /demo/jobs</c>.</summary>
public sealed class DemoTriggerBody
{
    public string? CallbackUrl { get; set; }

    /// <summary>Same secret as <c>WEBHOOK_SECRET</c> / <c>Webhook:Secret</c> when using signed callbacks.</summary>
    public string? CallbackSecret { get; set; }

    public string? IdempotencyKey { get; set; }
}

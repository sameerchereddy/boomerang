using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Boomerang.Client.Models;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Abstractions;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.AspNetCore.Routing;
using Boomerang.Client.AspNetCore;
using Xunit;

namespace Boomerang.Client.Tests;

public class BoomerangWebhookFilterTests
{
    [Fact]
    public async Task OnActionExecutionAsync_sets_payload_when_signature_valid()
    {
        var secret = "unit-test-hmac-secret-min-32-chars!!";
        var payload = new
        {
            jobId = Guid.NewGuid().ToString(),
            status = "DONE",
            result = (object?)null,
            completedAt = DateTimeOffset.Parse("2026-03-22T10:00:18Z"),
        };
        var bodyJson = JsonSerializer.Serialize(payload, BoomerangJson.SerializerOptions);
        var bodyBytes = Encoding.UTF8.GetBytes(bodyJson);
        var sig = "sha256=" + Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), bodyBytes)).ToLowerInvariant();

        var http = new DefaultHttpContext();
        http.Request.Method = "POST";
        http.Request.Headers["X-Signature-SHA256"] = sig;
        http.Request.Body = new MemoryStream(bodyBytes);

        var args = new Dictionary<string, object?> { ["payload"] = null! };
        var ac = new ActionContext(http, new RouteData(), new ActionDescriptor());
        var stubController = new object();
        var ctx = new ActionExecutingContext(ac, new List<IFilterMetadata>(), args, stubController);

        var filter = new BoomerangWebhookFilter(secret, "payload");
        await filter.OnActionExecutionAsync(ctx, () =>
            Task.FromResult(new ActionExecutedContext(ac, new List<IFilterMetadata>(), stubController)));

        Assert.Null(ctx.Result);
        Assert.IsType<BoomerangWebhookPayload>(args["payload"]);
    }

    [Fact]
    public async Task OnActionExecutionAsync_returns_401_when_signature_invalid()
    {
        var secret = "unit-test-hmac-secret-min-32-chars!!";
        var bodyBytes = Encoding.UTF8.GetBytes("{}");
        var http = new DefaultHttpContext();
        http.Request.Method = "POST";
        http.Request.Headers["X-Signature-SHA256"] = "sha256=deadbeef";
        http.Request.Body = new MemoryStream(bodyBytes);

        var args = new Dictionary<string, object?> { ["payload"] = null! };
        var ac = new ActionContext(http, new RouteData(), new ActionDescriptor());
        var stubController = new object();
        var ctx = new ActionExecutingContext(ac, new List<IFilterMetadata>(), args, stubController);

        var filter = new BoomerangWebhookFilter(secret, "payload");
        await filter.OnActionExecutionAsync(ctx, () =>
            throw new InvalidOperationException("next should not run"));

        Assert.IsType<UnauthorizedObjectResult>(ctx.Result);
    }
}

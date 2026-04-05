using Boomerang.Client.AspNetCore;
using Boomerang.Client.Models;
using Microsoft.AspNetCore.Mvc;

namespace Boomerang.Sample.Web.Controllers;

[ApiController]
[Route("hooks")]
public sealed class WebhooksController : ControllerBase
{
    private readonly LastWebhookStore _lastWebhook;

    public WebhooksController(LastWebhookStore lastWebhook) =>
        _lastWebhook = lastWebhook;

    /// <summary>Boomerang POSTs here when a job completes; <c>X-Signature-SHA256</c> is verified when <c>callbackSecret</c> was set.</summary>
    [HttpPost("done")]
    [BoomerangWebhook(SecretEnvironmentVariable = "WEBHOOK_SECRET")]
    public ActionResult<object> OnDone([FromBody] BoomerangWebhookPayload payload)
    {
        _lastWebhook.Record(payload);
        return Ok(new
        {
            received = true,
            payload.JobId,
            payload.Status,
            payload.CompletedAt,
        });
    }
}

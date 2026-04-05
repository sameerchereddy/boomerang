using System.Text.Json;

namespace Boomerang.Client.Models;

/// <summary>
/// Request body for <c>POST {basePath}</c> (enqueue job).
/// Mirrors the Java <c>BoomerangRequest</c> model.
/// </summary>
public sealed class BoomerangTriggerRequest
{
    /// <summary>HTTPS URL to receive the job result webhook.</summary>
    public string? CallbackUrl { get; init; }

    /// <summary>Optional secret (min 32 chars when set); enables <c>X-Signature-SHA256</c> on callbacks.</summary>
    public string? CallbackSecret { get; init; }

    /// <summary>Optional idempotency key; duplicates within cooldown return 409.</summary>
    public string? IdempotencyKey { get; init; }

    /// <summary>Opaque JSON passed to the handler / worker.</summary>
    public JsonElement? Payload { get; init; }

    /// <summary>Optional payload schema version string.</summary>
    public string? MessageVersion { get; init; }

    /// <summary>Standalone mode: HTTP URL Boomerang POSTs to instead of an in-process handler.</summary>
    public string? WorkerUrl { get; init; }
}

using System.Text.Json;

namespace Boomerang.Client.Models;

/// <summary>
/// JSON body Boomerang POSTs to <see cref="BoomerangTriggerRequest.CallbackUrl"/> when a job completes.
/// Mirrors Java <c>BoomerangPayload</c> (jobId, status, result, completedAt, error, boomerangVersion).
/// </summary>
public sealed class BoomerangWebhookPayload
{
    /// <summary>Protocol version. Currently <c>"1"</c>.</summary>
    public string BoomerangVersion { get; init; } = "";

    public string JobId { get; init; } = "";

    /// <summary><c>DONE</c> or <c>FAILED</c>.</summary>
    public string Status { get; init; } = "";

    /// <summary>Handler result for success. Check <see cref="JsonElement.ValueKind"/> for undefined/null JSON.</summary>
    public JsonElement Result { get; init; }

    public DateTimeOffset CompletedAt { get; init; }

    /// <summary>Error message when <see cref="Status"/> is <c>FAILED</c>. Null on success.</summary>
    public string? Error { get; init; }
}

using System.Text.Json;

namespace Boomerang.Client.Models;

/// <summary>
/// JSON body Boomerang POSTs to <see cref="BoomerangTriggerRequest.CallbackUrl"/> when a job completes.
/// Mirrors Java <c>BoomerangPayload</c> (jobId, status, result, completedAt).
/// </summary>
public sealed class BoomerangWebhookPayload
{
    public string JobId { get; init; } = "";

    /// <summary><c>DONE</c> or <c>FAILED</c>.</summary>
    public string Status { get; init; } = "";

    /// <summary>Handler result for success. Check <see cref="JsonElement.ValueKind"/> for undefined/null JSON.</summary>
    public JsonElement Result { get; init; }

    public DateTimeOffset CompletedAt { get; init; }
}

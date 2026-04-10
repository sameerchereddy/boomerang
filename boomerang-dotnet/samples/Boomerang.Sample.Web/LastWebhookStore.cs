using System.Text.Json;
using Boomerang.Client.Models;

namespace Boomerang.Sample.Web;

/// <summary>In-memory last webhook for the dashboard demo (single process only).</summary>
public sealed class LastWebhookStore
{
    private readonly object _gate = new();
    private LastWebhookSnapshot? _last;

    public void Record(BoomerangWebhookPayload payload)
    {
        string? resultJson = null;
        if (payload.Result.ValueKind != JsonValueKind.Undefined && payload.Result.ValueKind != JsonValueKind.Null)
            resultJson = payload.Result.GetRawText();

        lock (_gate)
        {
            _last = new LastWebhookSnapshot(
                ReceivedAtUtc: DateTimeOffset.UtcNow,
                payload.JobId,
                payload.Status,
                payload.CompletedAt,
                resultJson);
        }
    }

    public LastWebhookSnapshot? GetLast()
    {
        lock (_gate)
            return _last;
    }
}

public sealed record LastWebhookSnapshot(
    DateTimeOffset ReceivedAtUtc,
    string JobId,
    string Status,
    DateTimeOffset CompletedAt,
    string? ResultJson);

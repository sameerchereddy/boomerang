namespace Boomerang.Client.Models;

/// <summary>
/// Job status from <c>GET {basePath}/{jobId}</c>. Mirrors Java <c>BoomerangJobStatus</c>.
/// </summary>
public sealed class BoomerangJobStatusResponse
{
    public string JobId { get; init; } = "";

    /// <summary>One of <c>PENDING</c>, <c>IN_PROGRESS</c>, <c>DONE</c>, <c>FAILED</c>.</summary>
    public string Status { get; init; } = "";

    public DateTimeOffset CreatedAt { get; init; }

    public DateTimeOffset? CompletedAt { get; init; }
}

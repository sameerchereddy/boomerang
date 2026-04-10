using System.Net;

namespace Boomerang.Client.Exceptions;

/// <summary>
/// Thrown when the API returns 409 (idempotency cooldown / duplicate job).
/// </summary>
public sealed class BoomerangConflictException : BoomerangApiException
{
    /// <summary>Server-provided retry hint in seconds, if present.</summary>
    public long? RetryAfterSeconds { get; }

    public BoomerangConflictException(string? responseBody, long? retryAfterSeconds)
        : base(HttpStatusCode.Conflict, responseBody, "Conflict (409): job already in progress or recently completed.")
    {
        RetryAfterSeconds = retryAfterSeconds;
    }
}

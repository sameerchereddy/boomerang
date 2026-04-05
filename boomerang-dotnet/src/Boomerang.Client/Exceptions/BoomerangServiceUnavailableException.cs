namespace Boomerang.Client.Exceptions;

/// <summary>
/// Thrown when the API returns 503.
/// </summary>
public sealed class BoomerangServiceUnavailableException : BoomerangApiException
{
    public BoomerangServiceUnavailableException(string? responseBody)
        : base(503, responseBody, "Service unavailable (503).") { }
}

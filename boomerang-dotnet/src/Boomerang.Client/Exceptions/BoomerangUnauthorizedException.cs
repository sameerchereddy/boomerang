namespace Boomerang.Client.Exceptions;

/// <summary>
/// Thrown when the API returns 401 (missing or invalid JWT).
/// </summary>
public sealed class BoomerangUnauthorizedException : BoomerangApiException
{
    public BoomerangUnauthorizedException(string? responseBody)
        : base(401, responseBody, "Authentication failed (401).") { }
}

using System.Net;

namespace Boomerang.Client.Exceptions;

/// <summary>
/// Thrown when the API returns 401 (missing or invalid JWT).
/// </summary>
public sealed class BoomerangUnauthorizedException : BoomerangApiException
{
    public BoomerangUnauthorizedException(string? responseBody)
        : base(HttpStatusCode.Unauthorized, responseBody, "Authentication failed (401).") { }
}

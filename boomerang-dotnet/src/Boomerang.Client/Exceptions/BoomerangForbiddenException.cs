namespace Boomerang.Client.Exceptions;

/// <summary>
/// Thrown when the API returns 403 (e.g. callback or worker URL not in allowlist).
/// </summary>
public sealed class BoomerangForbiddenException : BoomerangApiException
{
    public BoomerangForbiddenException(string? responseBody)
        : base(403, responseBody, "Request forbidden (403).") { }
}

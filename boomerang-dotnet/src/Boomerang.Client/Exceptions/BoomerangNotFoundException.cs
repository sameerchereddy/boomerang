using System.Net;

namespace Boomerang.Client.Exceptions;

/// <summary>
/// Thrown when the API returns 404 (unknown job or not owned by caller).
/// </summary>
public sealed class BoomerangNotFoundException : BoomerangApiException
{
    public BoomerangNotFoundException(string? responseBody)
        : base(HttpStatusCode.NotFound, responseBody, "Resource not found (404).") { }
}

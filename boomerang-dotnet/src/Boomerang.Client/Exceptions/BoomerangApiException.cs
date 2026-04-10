using System.Net;

namespace Boomerang.Client.Exceptions;

/// <summary>
/// Base exception for non-success HTTP responses from the Boomerang API.
/// </summary>
public class BoomerangApiException : Exception
{
    /// <summary>HTTP status code returned by the server.</summary>
    public HttpStatusCode StatusCode { get; }

    /// <summary>Raw response body, if any.</summary>
    public string? ResponseBody { get; }

    public BoomerangApiException(HttpStatusCode statusCode, string? responseBody, string? message = null)
        : base(message ?? $"Boomerang API returned HTTP {(int)statusCode}.")
    {
        StatusCode = statusCode;
        ResponseBody = responseBody;
    }
}

namespace Boomerang.Client;

/// <summary>
/// Configuration for <see cref="BoomerangClient"/>.
/// </summary>
public sealed class BoomerangClientOptions
{
    /// <summary>Base URL of the Boomerang deployment (e.g. <c>https://boomerang.example.com</c>).</summary>
    public Uri BaseUrl { get; init; } = null!;

    /// <summary>API prefix (default <c>/jobs</c>). Must match server <c>boomerang.base-path</c>.</summary>
    public string ApiPath { get; init; } = "/jobs";

    /// <summary>Static Bearer token. Ignored when <see cref="GetTokenAsync"/> is set.</summary>
    public string? Token { get; init; }

    /// <summary>Optional async token provider (e.g. MSAL). When set, overrides <see cref="Token"/>.</summary>
    public Func<CancellationToken, Task<string>>? GetTokenAsync { get; init; }

    /// <summary>Optional shared <see cref="HttpClient"/> (caller owns lifetime when provided).</summary>
    public HttpClient? HttpClient { get; init; }
}

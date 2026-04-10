namespace Boomerang.Sample.Web;

/// <summary>Configuration for <see cref="Boomerang.Client.BoomerangClient"/> (maps to Boomerang:*)</summary>
public sealed class BoomerangOptions
{
    public const string SectionName = "Boomerang";

    /// <summary>Base URL of the Boomerang deployment (e.g. https://localhost:8080/).</summary>
    public string? BaseUrl { get; set; }

    /// <summary>API path prefix; must match server boomerang.base-path (default /jobs).</summary>
    public string ApiPath { get; set; } = "/jobs";

    /// <summary>Static Bearer JWT. Ignored when <see cref="JwtSecret"/> is set.</summary>
    public string? Jwt { get; set; }

    /// <summary>HS256 secret matching server <c>boomerang.auth.jwt-secret</c>; sample mints short-lived tokens for local dev.</summary>
    public string? JwtSecret { get; set; }

    /// <summary>Value for the JWT <c>sub</c> claim when minting via <see cref="JwtSecret"/>.</summary>
    public string JwtSub { get; set; } = "boomerang-sample";

    /// <summary>Public base URL of this sample (e.g. https://localhost:7123) used to build default callback URLs for demos.</summary>
    public string? SamplePublicBaseUrl { get; set; }
}

using Boomerang.Client;

namespace Boomerang.Sample.Web;

/// <summary>Resolves whether Boomerang demo API calls are allowed and exposes a shared <see cref="BoomerangClient"/>.</summary>
public sealed class BoomerangDemoServices : IDisposable
{
    public BoomerangDemoServices(bool isReady, string? notReadyReason, BoomerangClient? client)
    {
        IsReady = isReady;
        NotReadyReason = notReadyReason;
        Client = client;
    }

    public bool IsReady { get; }

    public string? NotReadyReason { get; }

    public BoomerangClient? Client { get; }

    public void Dispose() => Client?.Dispose();

    public static BoomerangDemoServices Create(BoomerangOptions options)
    {
        var (ok, reason) = Validate(options);
        if (!ok)
            return new BoomerangDemoServices(false, reason, null);

        var client = BuildClient(options);
        return new BoomerangDemoServices(true, null, client);
    }

    private static (bool Ok, string? Reason) Validate(BoomerangOptions o)
    {
        if (string.IsNullOrWhiteSpace(o.BaseUrl))
            return (false, "Set Boomerang:BaseUrl (or environment variable BOOMERANG__BASEURL).");

        if (!Uri.TryCreate(o.BaseUrl.Trim(), UriKind.Absolute, out _))
            return (false, "Boomerang:BaseUrl must be an absolute URI.");

        var hasToken = !string.IsNullOrWhiteSpace(o.Jwt);
        var hasSecret = !string.IsNullOrWhiteSpace(o.JwtSecret);
        if (!hasToken && !hasSecret)
            return (false, "Set Boomerang:Jwt (BOOMERANG__JWT) or Boomerang:JwtSecret (BOOMERANG__JWTSECRET) for API calls.");

        return (true, null);
    }

    private static BoomerangClient BuildClient(BoomerangOptions o)
    {
        var baseUri = new Uri(o.BaseUrl!.Trim().TrimEnd('/') + "/", UriKind.Absolute);

        if (!string.IsNullOrWhiteSpace(o.JwtSecret))
        {
            var secret = o.JwtSecret!;
            var sub = string.IsNullOrWhiteSpace(o.JwtSub) ? "boomerang-sample" : o.JwtSub.Trim();
            return new BoomerangClient(new BoomerangClientOptions
            {
                BaseUrl = baseUri,
                ApiPath = o.ApiPath,
                GetTokenAsync = _ => Task.FromResult(DevJwt.CreateToken(secret, sub, TimeSpan.FromMinutes(10))),
            });
        }

        return new BoomerangClient(new BoomerangClientOptions
        {
            BaseUrl = baseUri,
            ApiPath = o.ApiPath,
            Token = o.Jwt!.Trim(),
        });
    }
}

namespace Boomerang.Sample.Web;

/// <summary>Resolves the callback URL for enqueue (explicit body vs <see cref="BoomerangOptions.SamplePublicBaseUrl"/>).</summary>
public static class CallbackUrlResolver
{
    public static string? Resolve(string? explicitUrl, BoomerangOptions options)
    {
        if (!string.IsNullOrWhiteSpace(explicitUrl))
            return explicitUrl.Trim();

        var basePublic = options.SamplePublicBaseUrl?.Trim().TrimEnd('/');
        if (string.IsNullOrEmpty(basePublic))
            return null;

        return $"{basePublic}/hooks/done";
    }
}

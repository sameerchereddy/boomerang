namespace Boomerang.Client.Models;

/// <summary>
/// Response from <c>POST {basePath}</c> when accepted (HTTP 202).
/// </summary>
public sealed class BoomerangTriggerResponse
{
    public string JobId { get; init; } = "";
}

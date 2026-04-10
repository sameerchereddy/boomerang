using System.Text.Json;
using System.Text.Json.Serialization;

namespace Boomerang.Client;

/// <summary>Shared <see cref="JsonSerializerOptions"/> for Boomerang JSON (camelCase, nulls omitted on write).</summary>
public static class BoomerangJson
{
    /// <summary>Options aligned with typical Spring Boot defaults.</summary>
    public static JsonSerializerOptions SerializerOptions { get; } = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        PropertyNameCaseInsensitive = true,
    };
}

using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Extensions.DependencyInjection;

namespace Boomerang.Client.AspNetCore;

/// <summary>
/// Verifies <c>X-Signature-SHA256</c> on the request body before the action runs.
/// Apply to a webhook action that accepts <see cref="Boomerang.Client.Models.BoomerangWebhookPayload"/>.
/// </summary>
/// <remarks>
/// Set either <see cref="Secret"/> (not recommended for production) or <see cref="SecretEnvironmentVariable"/>.
/// </remarks>
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Method)]
public sealed class BoomerangWebhookAttribute : Attribute, IFilterFactory
{
    /// <summary>Raw shared secret (same as enqueue <c>callbackSecret</c>). Prefer env-based configuration.</summary>
    public string? Secret { get; init; }

    /// <summary>Environment variable name to read the secret from.</summary>
    public string? SecretEnvironmentVariable { get; init; }

    /// <summary>Action parameter name to bind the deserialized payload to (default <c>payload</c>).</summary>
    public string PayloadParameterName { get; init; } = "payload";

    /// <inheritdoc />
    public bool IsReusable => false;

    /// <inheritdoc />
    public IFilterMetadata CreateInstance(IServiceProvider serviceProvider)
    {
        var secret = Secret;
        if (string.IsNullOrEmpty(secret) && !string.IsNullOrEmpty(SecretEnvironmentVariable))
            secret = Environment.GetEnvironmentVariable(SecretEnvironmentVariable);
        if (string.IsNullOrEmpty(secret))
            throw new InvalidOperationException(
                "Boomerang webhook: set Secret or SecretEnvironmentVariable on [BoomerangWebhook].");

        return ActivatorUtilities.CreateInstance<BoomerangWebhookFilter>(
            serviceProvider, secret, PayloadParameterName);
    }
}

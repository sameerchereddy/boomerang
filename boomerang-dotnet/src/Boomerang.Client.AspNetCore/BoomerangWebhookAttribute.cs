using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Extensions.DependencyInjection;

namespace Boomerang.Client.AspNetCore;

/// <summary>
/// Verifies <c>X-Signature-SHA256</c> on the request body before the action runs.
/// Apply to a webhook action that accepts <see cref="Boomerang.Client.Models.BoomerangWebhookPayload"/>.
/// </summary>
/// <remarks>
/// Set either <see cref="Secret"/> (not recommended for production) or <see cref="SecretEnvironmentVariable"/>.
/// The resolved secret is cached at first use — changes to the environment variable after app start are not picked up.
/// </remarks>
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Method)]
public sealed class BoomerangWebhookAttribute : Attribute, IFilterFactory
{
    private string? _resolvedSecret;

    /// <summary>Raw shared secret (same as enqueue <c>callbackSecret</c>). Prefer env-based configuration.</summary>
    public string? Secret { get; init; }

    /// <summary>Environment variable name to read the secret from.</summary>
    public string? SecretEnvironmentVariable { get; init; }

    /// <summary>Action parameter name to bind the deserialized payload to (default <c>payload</c>).</summary>
    public string PayloadParameterName { get; init; } = "payload";

    /// <inheritdoc />
    /// <remarks>The filter is stateless and safe to reuse across requests.</remarks>
    public bool IsReusable => true;

    /// <inheritdoc />
    public IFilterMetadata CreateInstance(IServiceProvider serviceProvider)
    {
        if (_resolvedSecret == null)
        {
            var secret = Secret;
            if (string.IsNullOrEmpty(secret) && !string.IsNullOrEmpty(SecretEnvironmentVariable))
                secret = Environment.GetEnvironmentVariable(SecretEnvironmentVariable);
            if (string.IsNullOrEmpty(secret))
                throw new InvalidOperationException(
                    "Boomerang webhook: set Secret or SecretEnvironmentVariable on [BoomerangWebhook].");
            _resolvedSecret = secret;
        }

        return ActivatorUtilities.CreateInstance<BoomerangWebhookFilter>(
            serviceProvider, _resolvedSecret, PayloadParameterName);
    }
}

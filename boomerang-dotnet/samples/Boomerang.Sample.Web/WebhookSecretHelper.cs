using Microsoft.Extensions.Configuration;

namespace Boomerang.Sample.Web;

public static class WebhookSecretHelper
{
    public static bool IsConfigured(IConfiguration configuration)
    {
        if (!string.IsNullOrWhiteSpace(configuration["Webhook:Secret"]))
            return true;
        return !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("WEBHOOK_SECRET"));
    }
}

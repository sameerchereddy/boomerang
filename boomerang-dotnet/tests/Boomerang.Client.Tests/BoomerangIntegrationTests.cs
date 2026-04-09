using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Boomerang.Client.Models;
using Microsoft.IdentityModel.Tokens;
using Xunit;

namespace Boomerang.Client.Tests;

/// <summary>
/// Set <c>BOOMERANG_TEST_BASE_URL</c> (e.g. http://localhost:8080/) and <c>BOOMERANG_JWT_SECRET</c> to run against a live Boomerang instance + Redis.
/// Integration scaffolding is in place, but full issue #10 coverage requires running this test in CI against a live Boomerang instance.
/// </summary>
public class BoomerangIntegrationTests
{
    private static bool Configured =>
        !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("BOOMERANG_TEST_BASE_URL"))
        && !string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("BOOMERANG_JWT_SECRET"));

    [Fact]
    public async Task Trigger_and_poll_roundtrip_when_env_configured()
    {
        if (!Configured)
            return;

        var baseUrl = Environment.GetEnvironmentVariable("BOOMERANG_TEST_BASE_URL")!.TrimEnd('/') + "/";
        var jwtSecret = Environment.GetEnvironmentVariable("BOOMERANG_JWT_SECRET")!;
        var token = CreateJwt(jwtSecret, "dotnet-sdk-test", TimeSpan.FromMinutes(10));

        using var client = new BoomerangClient(new BoomerangClientOptions
        {
            BaseUrl = new Uri(baseUrl),
            Token = token,
        });

        var trigger = await client.TriggerAsync(new BoomerangTriggerRequest
        {
            // This test validates trigger+poll only, so a dummy callback URL is intentional.
            CallbackUrl = "https://webhook.site/00000000-0000-0000-0000-000000000000",
        });

        Assert.False(string.IsNullOrEmpty(trigger.JobId));

        var status = await client.PollAsync(trigger.JobId);
        Assert.Equal(trigger.JobId, status.JobId);
        Assert.NotNull(status.Status);
    }

    private static string CreateJwt(string secret, string sub, TimeSpan lifetime)
    {
        var key = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(secret));
        var creds = new SigningCredentials(key, SecurityAlgorithms.HmacSha256);
        var token = new JwtSecurityToken(
            claims: new[] { new Claim(JwtRegisteredClaimNames.Sub, sub) },
            expires: DateTime.UtcNow.Add(lifetime),
            signingCredentials: creds);
        return new JwtSecurityTokenHandler().WriteToken(token);
    }
}

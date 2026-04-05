using System.Security.Cryptography;
using System.Text;
using Xunit;

namespace Boomerang.Client.Tests;

public class BoomerangSignatureTests
{
    [Fact]
    public void Verify_accepts_valid_hmac()
    {
        var secret = "boomerang-dev-secret-key-min-32-chars!!";
        var body = Encoding.UTF8.GetBytes("""{"jobId":"x","status":"DONE","completedAt":"2026-01-01T00:00:00Z"}""");
        var expectedFull = "sha256=" + Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), body))
            .ToLowerInvariant();

        Assert.True(BoomerangSignature.Verify(body, expectedFull, secret));
    }

    [Fact]
    public void Verify_rejects_wrong_secret()
    {
        var body = Encoding.UTF8.GetBytes("""{"a":1}""");
        var sig = "sha256=" + Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes("secret-one-at-least-32-chars-long!!"), body))
            .ToLowerInvariant();

        Assert.False(BoomerangSignature.Verify(body, sig, "secret-two-at-least-32-chars-long!!"));
    }

    [Fact]
    public void Verify_rejects_null_or_mismatched_length_header()
    {
        var body = Encoding.UTF8.GetBytes("{}");
        Assert.False(BoomerangSignature.Verify(body, null, "x"));
        Assert.False(BoomerangSignature.Verify(body, "", "x"));
        Assert.False(BoomerangSignature.Verify(body, "sha256=abc", "secret-at-least-32-characters-long!!"));
    }
}

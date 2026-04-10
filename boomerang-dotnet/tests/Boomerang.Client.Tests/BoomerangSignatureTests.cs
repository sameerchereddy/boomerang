using System.Security.Cryptography;
using System.Text;
using Xunit;

namespace Boomerang.Client.Tests;

public class BoomerangSignatureTests
{
    private const string Secret = "boomerang-dev-secret-key-min-32-chars!!";

    private static string ComputeSignature(byte[] body, string secret) =>
        "sha256=" + Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), body))
            .ToLowerInvariant();

    [Fact]
    public void Verify_accepts_valid_hmac()
    {
        var body = Encoding.UTF8.GetBytes("""{"jobId":"x","status":"DONE","completedAt":"2026-01-01T00:00:00Z"}""");
        var sig = ComputeSignature(body, Secret);
        Assert.True(BoomerangSignature.Verify(body, sig, Secret));
    }

    [Fact]
    public void Verify_rejects_wrong_secret()
    {
        var body = Encoding.UTF8.GetBytes("""{"a":1}""");
        var sig = ComputeSignature(body, "secret-one-at-least-32-chars-long!!");
        Assert.False(BoomerangSignature.Verify(body, sig, "secret-two-at-least-32-chars-long!!"));
    }

    [Fact]
    public void Verify_rejects_tampered_body()
    {
        var body = Encoding.UTF8.GetBytes("""{"jobId":"x","status":"DONE"}""");
        var sig = ComputeSignature(body, Secret);
        var tamperedBody = Encoding.UTF8.GetBytes("""{"jobId":"x","status":"FAILED"}""");
        Assert.False(BoomerangSignature.Verify(tamperedBody, sig, Secret));
    }

    [Fact]
    public void Verify_rejects_null_header()
    {
        var body = Encoding.UTF8.GetBytes("{}");
        Assert.False(BoomerangSignature.Verify(body, null, Secret));
    }

    [Fact]
    public void Verify_rejects_empty_header()
    {
        var body = Encoding.UTF8.GetBytes("{}");
        Assert.False(BoomerangSignature.Verify(body, "", Secret));
    }

    [Fact]
    public void Verify_rejects_uppercase_hex_in_header()
    {
        // Contract requires lowercase hex — uppercase must be rejected.
        var body = Encoding.UTF8.GetBytes("""{"jobId":"x"}""");
        var lowerSig = ComputeSignature(body, Secret);
        var upperSig = lowerSig.Replace("sha256=", "sha256=").ToUpperInvariant();
        // Rebuild with correct prefix
        var hexPart = Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(Secret), body)); // already uppercase
        var upperHeader = "sha256=" + hexPart; // uppercase hex
        Assert.False(BoomerangSignature.Verify(body, upperHeader, Secret));
    }

    [Fact]
    public void Verify_rejects_missing_sha256_prefix()
    {
        // Header must start with "sha256=" — bare hex is rejected.
        var body = Encoding.UTF8.GetBytes("""{"jobId":"x"}""");
        var bareHex = Convert.ToHexString(HMACSHA256.HashData(Encoding.UTF8.GetBytes(Secret), body)).ToLowerInvariant();
        Assert.False(BoomerangSignature.Verify(body, bareHex, Secret));
    }

    [Fact]
    public void Verify_rejects_mismatched_length_header()
    {
        var body = Encoding.UTF8.GetBytes("{}");
        Assert.False(BoomerangSignature.Verify(body, "sha256=abc", Secret));
    }
}

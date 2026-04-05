using System.Security.Cryptography;
using System.Text;

namespace Boomerang.Client;

/// <summary>
/// Verifies <c>X-Signature-SHA256</c> on Boomerang webhook (and worker) callbacks.
/// Contract: HMAC-SHA256 over raw UTF-8 body bytes; header value <c>sha256=&lt;lowercase hex&gt;</c>.
/// </summary>
public static class BoomerangSignature
{
    /// <summary>
    /// Verifies the signature header against the body using the shared secret (constant-time comparison).
    /// </summary>
    /// <param name="bodyUtf8">Exact UTF-8 bytes of the HTTP body Boomerang signed.</param>
    /// <param name="signatureHeader">Value of <c>X-Signature-SHA256</c> (e.g. <c>sha256=abc...</c>).</param>
    /// <param name="secret">Shared HMAC secret (same as <c>callbackSecret</c> sent when enqueueing).</param>
    /// <returns><c>true</c> if the signature is valid.</returns>
    public static bool Verify(ReadOnlySpan<byte> bodyUtf8, string? signatureHeader, string secret)
    {
        if (string.IsNullOrEmpty(signatureHeader) || string.IsNullOrEmpty(secret))
            return false;

        var expectedFull = "sha256=" + Convert.ToHexString(
                HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), bodyUtf8))
            .ToLowerInvariant();

        var expectedBytes = Encoding.UTF8.GetBytes(expectedFull);
        var actualBytes = Encoding.UTF8.GetBytes(signatureHeader);
        if (expectedBytes.Length != actualBytes.Length)
            return false;

        return CryptographicOperations.FixedTimeEquals(expectedBytes, actualBytes);
    }
}

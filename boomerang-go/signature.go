package boomerang

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
)

// Verify returns true if signatureHeader is a valid HMAC-SHA256 signature for
// body using secret. Uses hmac.Equal for constant-time comparison.
//
// signatureHeader must be in the form "sha256=<lowercase hex>", exactly as
// sent in the X-Signature-SHA256 header.
func Verify(body []byte, signatureHeader, secret string) bool {
	expected := Compute(body, secret)
	return hmac.Equal([]byte(expected), []byte(signatureHeader))
}

// Compute returns the expected X-Signature-SHA256 header value for body:
// "sha256=<lowercase hex>".
func Compute(body []byte, secret string) string {
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write(body)
	return "sha256=" + hex.EncodeToString(mac.Sum(nil))
}

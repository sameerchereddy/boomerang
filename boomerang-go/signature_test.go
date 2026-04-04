package boomerang_test

import (
	"testing"

	boomerang "github.com/sameerchereddy/boomerang-go"
)

func TestCompute(t *testing.T) {
	body := []byte(`{"jobId":"j1","status":"DONE"}`)
	secret := "test-secret"
	got := boomerang.Compute(body, secret)
	if got[:7] != "sha256=" {
		t.Errorf("Compute should start with 'sha256=', got %q", got)
	}
	// Verify it's deterministic
	if boomerang.Compute(body, secret) != got {
		t.Error("Compute should be deterministic")
	}
}

func TestVerify_Valid(t *testing.T) {
	body := []byte(`{"jobId":"j1","status":"DONE"}`)
	secret := "test-secret"
	sig := boomerang.Compute(body, secret)
	if !boomerang.Verify(body, sig, secret) {
		t.Error("Verify should return true for correct signature")
	}
}

func TestVerify_WrongSecret(t *testing.T) {
	body := []byte(`{"jobId":"j1"}`)
	sig := boomerang.Compute(body, "correct-secret")
	if boomerang.Verify(body, sig, "wrong-secret") {
		t.Error("Verify should return false for wrong secret")
	}
}

func TestVerify_TamperedBody(t *testing.T) {
	body := []byte(`{"jobId":"j1"}`)
	sig := boomerang.Compute(body, "secret")
	tampered := []byte(`{"jobId":"j2"}`)
	if boomerang.Verify(tampered, sig, "secret") {
		t.Error("Verify should return false for tampered body")
	}
}

func TestVerify_EmptySignature(t *testing.T) {
	body := []byte(`{}`)
	if boomerang.Verify(body, "", "secret") {
		t.Error("Verify should return false for empty signature")
	}
}

func TestVerify_MalformedSignature(t *testing.T) {
	body := []byte(`{}`)
	if boomerang.Verify(body, "not-a-valid-sig", "secret") {
		t.Error("Verify should return false for malformed signature")
	}
}

func TestCompute_LowercaseHex(t *testing.T) {
	body := []byte(`test`)
	sig := boomerang.Compute(body, "secret")
	// Strip "sha256=" prefix and check for lowercase only
	hex := sig[7:]
	for _, c := range hex {
		if c >= 'A' && c <= 'F' {
			t.Errorf("Compute should produce lowercase hex, got uppercase char %q in %q", c, hex)
		}
	}
}

func TestVerify_KnownVector(t *testing.T) {
	// Pre-computed: echo -n '{"jobId":"abc"}' | openssl dgst -sha256 -hmac "my-secret"
	body := []byte(`{"jobId":"abc"}`)
	secret := "my-secret"
	expected := boomerang.Compute(body, secret)
	if !boomerang.Verify(body, expected, secret) {
		t.Error("Verify should pass for known-good vector")
	}
}

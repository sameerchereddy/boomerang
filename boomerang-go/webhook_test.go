package boomerang_test

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"

	boomerang "github.com/sameerchereddy/boomerang-go"
)

const webhookSecret = "webhook-test-secret"

func validWebhookBody() []byte {
	return []byte(`{
		"boomerangVersion":"1",
		"jobId":"j1",
		"status":"DONE",
		"completedAt":"2026-03-22T10:00:18Z",
		"result":{"key":"value"}
	}`)
}

func TestWebhookHandler_ValidSignature(t *testing.T) {
	body := validWebhookBody()
	sig := boomerang.Compute(body, webhookSecret)

	var received boomerang.Payload
	handler := boomerang.WebhookHandler(webhookSecret, func(p boomerang.Payload) {
		received = p
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	req.Header.Set("X-Signature-SHA256", sig)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("status: got %d, want 200", w.Code)
	}
	if received.JobID != "j1" {
		t.Errorf("JobID: got %q, want %q", received.JobID, "j1")
	}
	if received.Status != "DONE" {
		t.Errorf("Status: got %q, want %q", received.Status, "DONE")
	}
}

func TestWebhookHandler_InvalidSignature(t *testing.T) {
	body := validWebhookBody()

	handler := boomerang.WebhookHandler(webhookSecret, func(p boomerang.Payload) {
		t.Error("handler should not be called for invalid signature")
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	req.Header.Set("X-Signature-SHA256", "sha256=badhex")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status: got %d, want 401", w.Code)
	}
}

func TestWebhookHandler_MissingSignature(t *testing.T) {
	body := validWebhookBody()

	handler := boomerang.WebhookHandler(webhookSecret, func(p boomerang.Payload) {
		t.Error("handler should not be called for missing signature")
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	// No X-Signature-SHA256 header set
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status: got %d, want 401", w.Code)
	}
}

func TestWebhookHandler_InvalidJSON(t *testing.T) {
	body := []byte(`not json`)
	sig := boomerang.Compute(body, webhookSecret)

	handler := boomerang.WebhookHandler(webhookSecret, func(p boomerang.Payload) {
		t.Error("handler should not be called for invalid JSON")
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	req.Header.Set("X-Signature-SHA256", sig)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Errorf("status: got %d, want 400", w.Code)
	}
}

func TestWebhookHandler_FailedStatus(t *testing.T) {
	body := []byte(`{
		"boomerangVersion":"1",
		"jobId":"j2",
		"status":"FAILED",
		"completedAt":"2026-03-22T10:00:18Z",
		"error":"timeout"
	}`)
	sig := boomerang.Compute(body, webhookSecret)

	var received boomerang.Payload
	handler := boomerang.WebhookHandler(webhookSecret, func(p boomerang.Payload) {
		received = p
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	req.Header.Set("X-Signature-SHA256", sig)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("status: got %d, want 200", w.Code)
	}
	if received.Status != "FAILED" || received.Error != "timeout" {
		t.Errorf("unexpected payload: %+v", received)
	}
}

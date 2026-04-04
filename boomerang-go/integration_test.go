//go:build integration

package boomerang_test

import (
	"context"
	"errors"
	"os"
	"testing"

	boomerang "github.com/sameerchereddy/boomerang-go"
)

// These tests run against a real Boomerang service + Redis started via
// docker-compose (see docker-compose.test.yml). Run with:
//
//	make test-integration

func integrationClient(t *testing.T) *boomerang.Client {
	t.Helper()
	baseURL := os.Getenv("BOOMERANG_URL")
	token := os.Getenv("BOOMERANG_JWT")
	if baseURL == "" || token == "" {
		t.Skip("BOOMERANG_URL and BOOMERANG_JWT must be set for integration tests")
	}
	return boomerang.NewClient(boomerang.Config{BaseURL: baseURL, Token: token})
}

func TestIntegration_TriggerReturnsJobID(t *testing.T) {
	client := integrationClient(t)

	resp, err := client.Trigger(context.Background(), boomerang.TriggerRequest{
		WorkerURL:      os.Getenv("WIREMOCK_WORKER_URL"),
		CallbackURL:    os.Getenv("WIREMOCK_CALLBACK_URL"),
		IdempotencyKey: "integration-trigger-" + t.Name(),
	})
	if err != nil {
		t.Fatalf("Trigger failed: %v", err)
	}
	if resp.JobID == "" {
		t.Error("expected non-empty JobID")
	}
}

func TestIntegration_MissingJWT_401(t *testing.T) {
	baseURL := os.Getenv("BOOMERANG_URL")
	if baseURL == "" {
		t.Skip("BOOMERANG_URL must be set")
	}
	client := boomerang.NewClient(boomerang.Config{BaseURL: baseURL, Token: ""})
	_, err := client.Trigger(context.Background(), boomerang.TriggerRequest{
		CallbackURL: os.Getenv("WIREMOCK_CALLBACK_URL"),
	})
	if !errors.Is(err, boomerang.ErrUnauthorized) {
		t.Errorf("expected ErrUnauthorized, got %v", err)
	}
}

func TestIntegration_ExpiredJWT_401(t *testing.T) {
	baseURL := os.Getenv("BOOMERANG_URL")
	if baseURL == "" {
		t.Skip("BOOMERANG_URL must be set")
	}
	// A pre-generated expired HS256 JWT (exp in the past)
	expiredToken := os.Getenv("BOOMERANG_EXPIRED_JWT")
	if expiredToken == "" {
		t.Skip("BOOMERANG_EXPIRED_JWT must be set")
	}
	client := boomerang.NewClient(boomerang.Config{BaseURL: baseURL, Token: expiredToken})
	_, err := client.Trigger(context.Background(), boomerang.TriggerRequest{
		CallbackURL: os.Getenv("WIREMOCK_CALLBACK_URL"),
	})
	if !errors.Is(err, boomerang.ErrUnauthorized) {
		t.Errorf("expected ErrUnauthorized, got %v", err)
	}
}

func TestIntegration_DuplicateJob_409(t *testing.T) {
	client := integrationClient(t)
	req := boomerang.TriggerRequest{
		WorkerURL:      os.Getenv("WIREMOCK_WORKER_URL"),
		CallbackURL:    os.Getenv("WIREMOCK_CALLBACK_URL"),
		IdempotencyKey: "integration-test-duplicate-" + t.Name(),
	}

	_, err := client.Trigger(context.Background(), req)
	if err != nil {
		t.Fatalf("first Trigger failed: %v", err)
	}

	_, err = client.Trigger(context.Background(), req)
	if !errors.Is(err, boomerang.ErrConflict) {
		t.Errorf("expected ErrConflict on duplicate, got %v", err)
	}
}

func TestIntegration_ForbiddenCallbackURL_403(t *testing.T) {
	if os.Getenv("BOOMERANG_SKIP_URL_VALIDATION") == "true" {
		t.Skip("URL validation is disabled in this environment — 403 cannot be triggered")
	}
	client := integrationClient(t)
	_, err := client.Trigger(context.Background(), boomerang.TriggerRequest{
		WorkerURL:   os.Getenv("WIREMOCK_WORKER_URL"),
		CallbackURL: "https://evil.example.com/hook",
	})
	if !errors.Is(err, boomerang.ErrForbidden) {
		t.Errorf("expected ErrForbidden, got %v", err)
	}
}

func TestIntegration_Poll_ReturnsStatus(t *testing.T) {
	client := integrationClient(t)
	resp, err := client.Trigger(context.Background(), boomerang.TriggerRequest{
		WorkerURL:      os.Getenv("WIREMOCK_WORKER_URL"),
		CallbackURL:    os.Getenv("WIREMOCK_CALLBACK_URL"),
		IdempotencyKey: "integration-poll-" + t.Name(),
	})
	if err != nil {
		t.Fatalf("Trigger failed: %v", err)
	}

	status, err := client.Poll(context.Background(), resp.JobID)
	if err != nil {
		t.Fatalf("Poll failed: %v", err)
	}
	if status.JobID != resp.JobID {
		t.Errorf("JobID mismatch: got %q, want %q", status.JobID, resp.JobID)
	}
	if status.Status == "" {
		t.Error("Status should not be empty")
	}
}

func TestIntegration_Poll_OtherCallerJob_404(t *testing.T) {
	client := integrationClient(t)
	_, err := client.Poll(context.Background(), "00000000-0000-0000-0000-000000000000")
	var apiErr *boomerang.APIError
	if !errors.As(err, &apiErr) || apiErr.StatusCode != 404 {
		t.Errorf("expected 404 APIError, got %v", err)
	}
}

func TestIntegration_WebhookSignatureVerifiable(t *testing.T) {
	// After triggering a job, WireMock captures the webhook.
	// The integration test environment exposes the last webhook received via a
	// WireMock request journal endpoint so we can verify the signature.
	// This test is a placeholder — expand using the WireMock admin API.
	t.Log("Webhook signature verification via WireMock journal — see docker-compose.test.yml")
}

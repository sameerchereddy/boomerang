package boomerang_test

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	boomerang "github.com/sameerchereddy/boomerang-go"
)

func TestClient_Trigger_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/sync" {
			t.Errorf("unexpected request: %s %s", r.Method, r.URL.Path)
		}
		if r.Header.Get("Authorization") != "Bearer test-token" {
			t.Errorf("missing Authorization header")
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("missing Content-Type header")
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		json.NewEncoder(w).Encode(map[string]string{"jobId": "job-abc"})
	}))
	defer srv.Close()

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: "test-token"})
	resp, err := client.Trigger(context.Background(), boomerang.TriggerRequest{
		WorkerURL:   srv.URL + "/worker",
		CallbackURL: srv.URL + "/callback",
	})
	if err != nil {
		t.Fatal(err)
	}
	if resp.JobID != "job-abc" {
		t.Errorf("JobID: got %q, want %q", resp.JobID, "job-abc")
	}
}

func TestClient_Trigger_401(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		json.NewEncoder(w).Encode(map[string]string{"error": "missing JWT"})
	}))
	defer srv.Close()

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: ""})
	_, err := client.Trigger(context.Background(), boomerang.TriggerRequest{
		WorkerURL:   srv.URL + "/worker",
		CallbackURL: srv.URL + "/callback",
	})
	if !errors.Is(err, boomerang.ErrUnauthorized) {
		t.Errorf("expected ErrUnauthorized, got %v", err)
	}
}

func TestClient_Trigger_403(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		json.NewEncoder(w).Encode(map[string]string{"error": "URL not in allowlist"})
	}))
	defer srv.Close()

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: "t"})
	_, err := client.Trigger(context.Background(), boomerang.TriggerRequest{CallbackURL: "http://evil.com"})
	if !errors.Is(err, boomerang.ErrForbidden) {
		t.Errorf("expected ErrForbidden, got %v", err)
	}
}

func TestClient_Trigger_409(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		json.NewEncoder(w).Encode(map[string]string{"error": "duplicate job"})
	}))
	defer srv.Close()

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: "t"})
	_, err := client.Trigger(context.Background(), boomerang.TriggerRequest{CallbackURL: "http://x.com"})
	if !errors.Is(err, boomerang.ErrConflict) {
		t.Errorf("expected ErrConflict, got %v", err)
	}
}

func TestClient_Trigger_503(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusServiceUnavailable)
		json.NewEncoder(w).Encode(map[string]string{"error": "pool saturated"})
	}))
	defer srv.Close()

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: "t"})
	_, err := client.Trigger(context.Background(), boomerang.TriggerRequest{CallbackURL: "http://x.com"})
	if !errors.Is(err, boomerang.ErrServiceUnavailable) {
		t.Errorf("expected ErrServiceUnavailable, got %v", err)
	}
}

func TestClient_Poll_Success(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/sync/job-123" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"jobId":  "job-123",
			"status": "DONE",
		})
	}))
	defer srv.Close()

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: "t"})
	status, err := client.Poll(context.Background(), "job-123")
	if err != nil {
		t.Fatal(err)
	}
	if status.JobID != "job-123" || status.Status != "DONE" {
		t.Errorf("unexpected status: %+v", status)
	}
}

func TestClient_Poll_401(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: ""})
	_, err := client.Poll(context.Background(), "job-123")
	if !errors.Is(err, boomerang.ErrUnauthorized) {
		t.Errorf("expected ErrUnauthorized, got %v", err)
	}
}

func TestClient_Poll_404(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: "t"})
	_, err := client.Poll(context.Background(), "other-callers-job")
	var apiErr *boomerang.APIError
	if !errors.As(err, &apiErr) || apiErr.StatusCode != 404 {
		t.Errorf("expected 404 APIError, got %v", err)
	}
}

func TestClient_ContextCancellation(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// never respond
		<-r.Context().Done()
	}))
	defer srv.Close()

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	client := boomerang.NewClient(boomerang.Config{BaseURL: srv.URL, Token: "t"})
	_, err := client.Trigger(ctx, boomerang.TriggerRequest{CallbackURL: "http://x.com"})
	if err == nil {
		t.Error("expected error from cancelled context")
	}
}

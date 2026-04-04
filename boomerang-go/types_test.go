package boomerang_test

import (
	"encoding/json"
	"testing"
	"time"

	boomerang "github.com/sameerchereddy/boomerang-go"
)

func TestTriggerRequest_JSONRoundtrip(t *testing.T) {
	req := boomerang.TriggerRequest{
		WorkerURL:      "https://example.com/worker",
		CallbackURL:    "https://example.com/callback",
		CallbackSecret: "secret",
		IdempotencyKey: "key-123",
	}

	data, err := json.Marshal(req)
	if err != nil {
		t.Fatal(err)
	}

	var out boomerang.TriggerRequest
	if err := json.Unmarshal(data, &out); err != nil {
		t.Fatal(err)
	}

	if out.WorkerURL != req.WorkerURL {
		t.Errorf("WorkerURL: got %q, want %q", out.WorkerURL, req.WorkerURL)
	}
	if out.CallbackURL != req.CallbackURL {
		t.Errorf("CallbackURL: got %q, want %q", out.CallbackURL, req.CallbackURL)
	}
	if out.CallbackSecret != req.CallbackSecret {
		t.Errorf("CallbackSecret: got %q, want %q", out.CallbackSecret, req.CallbackSecret)
	}
	if out.IdempotencyKey != req.IdempotencyKey {
		t.Errorf("IdempotencyKey: got %q, want %q", out.IdempotencyKey, req.IdempotencyKey)
	}
}

func TestTriggerRequest_CamelCaseJSON(t *testing.T) {
	req := boomerang.TriggerRequest{
		WorkerURL:   "https://w.example.com",
		CallbackURL: "https://c.example.com",
	}
	data, _ := json.Marshal(req)
	var m map[string]any
	json.Unmarshal(data, &m)

	if _, ok := m["workerUrl"]; !ok {
		t.Error("expected camelCase key workerUrl")
	}
	if _, ok := m["callbackUrl"]; !ok {
		t.Error("expected camelCase key callbackUrl")
	}
	if _, ok := m["callbackSecret"]; ok {
		t.Error("omitempty: callbackSecret should be absent when empty")
	}
}

func TestTriggerResponse_JSONRoundtrip(t *testing.T) {
	resp := boomerang.TriggerResponse{JobID: "abc-123"}
	data, _ := json.Marshal(resp)

	var out boomerang.TriggerResponse
	json.Unmarshal(data, &out)

	if out.JobID != "abc-123" {
		t.Errorf("JobID: got %q, want %q", out.JobID, "abc-123")
	}
}

func TestTriggerResponse_DecodedFromJobId(t *testing.T) {
	raw := `{"jobId":"xyz-789"}`
	var resp boomerang.TriggerResponse
	json.Unmarshal([]byte(raw), &resp)
	if resp.JobID != "xyz-789" {
		t.Errorf("got %q, want %q", resp.JobID, "xyz-789")
	}
}

func TestJobStatus_Pending(t *testing.T) {
	raw := `{"jobId":"j1","status":"PENDING"}`
	var s boomerang.JobStatus
	json.Unmarshal([]byte(raw), &s)
	if s.JobID != "j1" || s.Status != "PENDING" {
		t.Errorf("unexpected: %+v", s)
	}
	if s.CompletedAt != nil {
		t.Error("CompletedAt should be nil for PENDING")
	}
}

func TestJobStatus_Done(t *testing.T) {
	raw := `{"jobId":"j2","status":"DONE","completedAt":"2026-03-22T10:00:18Z","result":{"key":"val"}}`
	var s boomerang.JobStatus
	json.Unmarshal([]byte(raw), &s)
	if s.Status != "DONE" {
		t.Errorf("Status: got %q", s.Status)
	}
	if s.CompletedAt == nil {
		t.Error("CompletedAt should be set")
	}
	if string(s.Result) != `{"key":"val"}` {
		t.Errorf("Result: got %s", s.Result)
	}
}

func TestPayload_Done(t *testing.T) {
	raw := `{
		"boomerangVersion":"1",
		"jobId":"j1",
		"status":"DONE",
		"completedAt":"2026-03-22T10:00:18Z",
		"result":{"report":"url"}
	}`
	var p boomerang.Payload
	if err := json.Unmarshal([]byte(raw), &p); err != nil {
		t.Fatal(err)
	}
	if p.BoomerangVersion != "1" {
		t.Errorf("BoomerangVersion: got %q", p.BoomerangVersion)
	}
	if p.JobID != "j1" {
		t.Errorf("JobID: got %q", p.JobID)
	}
	if p.Status != "DONE" {
		t.Errorf("Status: got %q", p.Status)
	}
	if p.CompletedAt.IsZero() {
		t.Error("CompletedAt should not be zero")
	}
}

func TestPayload_Failed(t *testing.T) {
	raw := `{
		"boomerangVersion":"1",
		"jobId":"j2",
		"status":"FAILED",
		"completedAt":"2026-03-22T10:00:18Z",
		"error":"timeout"
	}`
	var p boomerang.Payload
	json.Unmarshal([]byte(raw), &p)
	if p.Status != "FAILED" || p.Error != "timeout" {
		t.Errorf("unexpected: %+v", p)
	}
}

func TestPayload_CompletedAtParsed(t *testing.T) {
	raw := `{"boomerangVersion":"1","jobId":"j1","status":"DONE","completedAt":"2026-03-22T10:00:18Z"}`
	var p boomerang.Payload
	json.Unmarshal([]byte(raw), &p)
	want := time.Date(2026, 3, 22, 10, 0, 18, 0, time.UTC)
	if !p.CompletedAt.Equal(want) {
		t.Errorf("CompletedAt: got %v, want %v", p.CompletedAt, want)
	}
}

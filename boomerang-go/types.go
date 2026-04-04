package boomerang

import (
	"encoding/json"
	"time"
)

// Config holds the connection settings for a Client.
type Config struct {
	BaseURL string
	Token   string
}

// TriggerRequest is the body sent to POST /sync.
type TriggerRequest struct {
	WorkerURL      string `json:"workerUrl"`
	CallbackURL    string `json:"callbackUrl"`
	CallbackSecret string `json:"callbackSecret,omitempty"`
	IdempotencyKey string `json:"idempotencyKey,omitempty"`
}

// TriggerResponse is the body returned by POST /sync (202 Accepted).
type TriggerResponse struct {
	JobID string `json:"jobId"`
}

// JobStatus is the body returned by GET /sync/{jobId}.
type JobStatus struct {
	JobID       string          `json:"jobId"`
	Status      string          `json:"status"` // "PENDING", "IN_PROGRESS", "DONE", "FAILED"
	CreatedAt   *time.Time      `json:"createdAt,omitempty"`
	CompletedAt *time.Time      `json:"completedAt,omitempty"`
	Result      json.RawMessage `json:"result,omitempty"`
	Error       string          `json:"error,omitempty"`
}

// Payload is the webhook body delivered to the consumer's callbackUrl.
// Use json.Unmarshal on Result to decode into your own type.
type Payload struct {
	BoomerangVersion string          `json:"boomerangVersion"`
	JobID            string          `json:"jobId"`
	Status           string          `json:"status"` // "DONE", "FAILED"
	CompletedAt      time.Time       `json:"completedAt"`
	Result           json.RawMessage `json:"result,omitempty"`
	Error            string          `json:"error,omitempty"`
}

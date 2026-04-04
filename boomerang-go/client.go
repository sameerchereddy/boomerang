package boomerang

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
)

// Client wraps the Boomerang HTTP API.
type Client struct {
	cfg  Config
	http *http.Client
}

// NewClient creates a Client from cfg. A zero-value http.Client is used
// internally; inject a custom one via NewClientWithHTTP for testing or timeouts.
func NewClient(cfg Config) *Client {
	return &Client{cfg: cfg, http: &http.Client{}}
}

// NewClientWithHTTP creates a Client that uses the provided *http.Client.
func NewClientWithHTTP(cfg Config, hc *http.Client) *Client {
	return &Client{cfg: cfg, http: hc}
}

// Trigger calls POST /sync and returns the assigned job ID.
func (c *Client) Trigger(ctx context.Context, req TriggerRequest) (*TriggerResponse, error) {
	body, err := json.Marshal(req)
	if err != nil {
		return nil, err
	}

	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.BaseURL+"/sync", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Authorization", "Bearer "+c.cfg.Token)
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := c.http.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return nil, parseAPIError(resp)
	}

	var out TriggerResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
}

// Poll calls GET /sync/{jobID} and returns the current job status.
func (c *Client) Poll(ctx context.Context, jobID string) (*JobStatus, error) {
	httpReq, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		c.cfg.BaseURL+"/sync/"+url.PathEscape(jobID),
		nil,
	)
	if err != nil {
		return nil, err
	}
	httpReq.Header.Set("Authorization", "Bearer "+c.cfg.Token)

	resp, err := c.http.Do(httpReq)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode >= 300 {
		return nil, parseAPIError(resp)
	}

	var out JobStatus
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, err
	}
	return &out, nil
}

func parseAPIError(resp *http.Response) error {
	var body struct {
		Error string `json:"error"`
	}
	data, _ := io.ReadAll(resp.Body)
	_ = json.Unmarshal(data, &body)
	message := body.Error
	if message == "" {
		message = resp.Status
	}
	return &APIError{StatusCode: resp.StatusCode, Message: message}
}

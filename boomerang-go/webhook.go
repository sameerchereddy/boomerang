package boomerang

import (
	"encoding/json"
	"io"
	"net/http"
)

// WebhookHandler returns an http.Handler that:
//  1. Reads the raw request body.
//  2. Verifies the X-Signature-SHA256 header — returns 401 on failure.
//  3. Decodes the body into a Payload and calls fn.
//
// Usage:
//
//	http.Handle("/hooks/sync-done", boomerang.WebhookHandler(secret, func(p boomerang.Payload) {
//	    fmt.Println(p.JobID, p.Status)
//	}))
func WebhookHandler(secret string, fn func(Payload)) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "failed to read body", http.StatusInternalServerError)
			return
		}

		if !Verify(body, r.Header.Get("X-Signature-SHA256"), secret) {
			http.Error(w, "invalid signature", http.StatusUnauthorized)
			return
		}

		var payload Payload
		if err := json.Unmarshal(body, &payload); err != nil {
			http.Error(w, "invalid payload", http.StatusBadRequest)
			return
		}

		fn(payload)
		w.WriteHeader(http.StatusOK)
	})
}

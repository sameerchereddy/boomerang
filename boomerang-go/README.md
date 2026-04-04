# boomerang-go

Go SDK for the [Boomerang](https://github.com/sameerchereddy/boomerang) async webhook platform.

[![Go Reference](https://pkg.go.dev/badge/github.com/sameerchereddy/boomerang-go@v0.1.1.svg)](https://pkg.go.dev/github.com/sameerchereddy/boomerang-go@v0.1.1)
[![GitHub release](https://img.shields.io/github/v/release/sameerchereddy/boomerang-go?label=release)](https://github.com/sameerchereddy/boomerang-go/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## Installation

```bash
go get github.com/sameerchereddy/boomerang-go@latest
```

Requires Go 1.21+.

## Usage

### Trigger a job

```go
import boomerang "github.com/sameerchereddy/boomerang-go"

client := boomerang.NewClient(boomerang.Config{
    BaseURL: os.Getenv("BOOMERANG_URL"),
    Token:   os.Getenv("BOOMERANG_JWT"),
})

resp, err := client.Trigger(ctx, boomerang.TriggerRequest{
    WorkerURL:      "https://myapp.com/internal/do-work",
    CallbackURL:    "https://myapp.com/hooks/done",
    CallbackSecret: os.Getenv("WEBHOOK_SECRET"),
})
// resp.JobID is the assigned job identifier
```

### Poll job status

```go
status, err := client.Poll(ctx, resp.JobID)
// status.Status — "PENDING", "IN_PROGRESS", "DONE", "FAILED"
```

### Receive webhooks (net/http)

```go
http.Handle("/hooks/done", boomerang.WebhookHandler(
    os.Getenv("WEBHOOK_SECRET"),
    func(p boomerang.Payload) {
        // X-Signature-SHA256 already verified
        fmt.Println(p.JobID, p.Status)
    },
))
```

### Receive webhooks (Gin)

```go
import ginboomerang "github.com/sameerchereddy/boomerang-go/middleware/gin"

r.POST("/hooks/done", ginboomerang.GinWebhook(os.Getenv("WEBHOOK_SECRET")), func(c *gin.Context) {
    payload := c.MustGet(ginboomerang.PayloadKey).(boomerang.Payload)
})
```

### Verify signatures manually

```go
body, _ := io.ReadAll(r.Body)
sig := r.Header.Get("X-Signature-SHA256")

if !boomerang.Verify(body, sig, secret) {
    http.Error(w, "invalid signature", http.StatusUnauthorized)
    return
}
```

### Typed errors

```go
_, err := client.Trigger(ctx, req)

switch {
case errors.Is(err, boomerang.ErrUnauthorized):       // 401
case errors.Is(err, boomerang.ErrForbidden):          // 403
case errors.Is(err, boomerang.ErrConflict):           // 409 — duplicate job
case errors.Is(err, boomerang.ErrServiceUnavailable): // 503
}
```

## Running tests

```bash
# Unit tests
go test -race ./...

# Integration tests (requires Docker)
make test-integration
```

## License

Apache 2.0

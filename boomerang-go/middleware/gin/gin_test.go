package ginboomerang_test

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	boomerang "github.com/sameerchereddy/boomerang-go"
	ginboomerang "github.com/sameerchereddy/boomerang-go/middleware/gin"
)

func init() {
	gin.SetMode(gin.TestMode)
}

const secret = "test-secret"

func testBody() []byte {
	return []byte(`{
		"boomerangVersion":"1",
		"jobId":"j1",
		"status":"DONE",
		"completedAt":"2026-03-22T10:00:18Z"
	}`)
}

func TestGinWebhook_ValidSignature(t *testing.T) {
	body := testBody()
	sig := boomerang.Compute(body, secret)

	var received boomerang.Payload
	r := gin.New()
	r.POST("/hook", ginboomerang.GinWebhook(secret), func(c *gin.Context) {
		received = c.MustGet(ginboomerang.PayloadKey).(boomerang.Payload)
		c.Status(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	req.Header.Set("X-Signature-SHA256", sig)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("status: got %d, want 200", w.Code)
	}
	if received.JobID != "j1" {
		t.Errorf("JobID: got %q, want %q", received.JobID, "j1")
	}
}

func TestGinWebhook_InvalidSignature(t *testing.T) {
	body := testBody()

	r := gin.New()
	r.POST("/hook", ginboomerang.GinWebhook(secret), func(c *gin.Context) {
		t.Error("handler should not be called for invalid signature")
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	req.Header.Set("X-Signature-SHA256", "sha256=bad")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status: got %d, want 401", w.Code)
	}
}

func TestGinWebhook_MissingSignature(t *testing.T) {
	body := testBody()

	r := gin.New()
	r.POST("/hook", ginboomerang.GinWebhook(secret), func(c *gin.Context) {
		t.Error("handler should not be called")
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("status: got %d, want 401", w.Code)
	}
}

func TestGinWebhook_InvalidJSON(t *testing.T) {
	body := []byte(`not json`)
	sig := boomerang.Compute(body, secret)

	r := gin.New()
	r.POST("/hook", ginboomerang.GinWebhook(secret), func(c *gin.Context) {
		t.Error("handler should not be called for invalid JSON")
	})

	req := httptest.NewRequest(http.MethodPost, "/hook", bytes.NewReader(body))
	req.Header.Set("X-Signature-SHA256", sig)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Errorf("status: got %d, want 400", w.Code)
	}
}

func TestGinWebhook_PayloadKeyConstant(t *testing.T) {
	if ginboomerang.PayloadKey != "boomerangPayload" {
		t.Errorf("PayloadKey: got %q, want %q", ginboomerang.PayloadKey, "boomerangPayload")
	}
}

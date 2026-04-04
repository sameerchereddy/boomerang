// Package gin provides a Gin middleware for verifying Boomerang webhook signatures.
// Import this subpackage only if your application uses Gin — the root boomerang
// package has no Gin dependency.
package ginboomerang

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/gin-gonic/gin"
	boomerang "github.com/sameerchereddy/boomerang-go"
)

// PayloadKey is the key used to store the parsed Payload in the Gin context.
const PayloadKey = "boomerangPayload"

// GinWebhook returns a Gin middleware that:
//  1. Reads the raw request body.
//  2. Verifies the X-Signature-SHA256 header — aborts with 401 on failure.
//  3. Decodes the body into a boomerang.Payload and stores it in the context
//     under PayloadKey ("boomerangPayload").
//
// Usage:
//
//	r.POST("/hooks/sync-done", ginboomerang.GinWebhook(secret), func(c *gin.Context) {
//	    payload := c.MustGet(ginboomerang.PayloadKey).(boomerang.Payload)
//	})
func GinWebhook(secret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		body, err := io.ReadAll(c.Request.Body)
		if err != nil {
			c.AbortWithStatus(http.StatusInternalServerError)
			return
		}

		if !boomerang.Verify(body, c.GetHeader("X-Signature-SHA256"), secret) {
			c.AbortWithStatus(http.StatusUnauthorized)
			return
		}

		var payload boomerang.Payload
		if err := json.Unmarshal(body, &payload); err != nil {
			c.AbortWithStatus(http.StatusBadRequest)
			return
		}

		c.Set(PayloadKey, payload)
		c.Next()
	}
}

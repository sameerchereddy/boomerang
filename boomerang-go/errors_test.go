package boomerang_test

import (
	"errors"
	"testing"

	boomerang "github.com/sameerchereddy/boomerang-go"
)

func TestAPIError_Error(t *testing.T) {
	err := &boomerang.APIError{StatusCode: 500, Message: "Internal Server Error"}
	want := "boomerang: 500 Internal Server Error"
	if err.Error() != want {
		t.Errorf("got %q, want %q", err.Error(), want)
	}
}

func TestAPIError_IsImplemented(t *testing.T) {
	err := &boomerang.APIError{StatusCode: 401, Message: "bad token"}
	if !errors.Is(err, boomerang.ErrUnauthorized) {
		t.Error("errors.Is should match ErrUnauthorized for 401")
	}
	if errors.Is(err, boomerang.ErrConflict) {
		t.Error("errors.Is should not match ErrConflict for 401")
	}
}

func TestSentinels(t *testing.T) {
	cases := []struct {
		sentinel *boomerang.APIError
		code     int
	}{
		{boomerang.ErrUnauthorized, 401},
		{boomerang.ErrForbidden, 403},
		{boomerang.ErrConflict, 409},
		{boomerang.ErrServiceUnavailable, 503},
	}
	for _, tc := range cases {
		if tc.sentinel.StatusCode != tc.code {
			t.Errorf("sentinel StatusCode: got %d, want %d", tc.sentinel.StatusCode, tc.code)
		}
	}
}

func TestAPIError_IsMatchesByStatusCode(t *testing.T) {
	// Simulate what parseAPIError returns — a new *APIError with a message
	returned := &boomerang.APIError{StatusCode: 409, Message: "duplicate job within cooldown"}
	if !errors.Is(returned, boomerang.ErrConflict) {
		t.Error("errors.Is should match ErrConflict for returned 409 error")
	}
	if errors.Is(returned, boomerang.ErrUnauthorized) {
		t.Error("errors.Is should not match ErrUnauthorized for 409 error")
	}
}

func TestAPIError_CatchableAsGenericError(t *testing.T) {
	err := &boomerang.APIError{StatusCode: 502, Message: "Bad Gateway"}
	var apiErr *boomerang.APIError
	if !errors.As(err, &apiErr) {
		t.Error("errors.As should work for *APIError")
	}
	if apiErr.StatusCode != 502 {
		t.Errorf("StatusCode: got %d", apiErr.StatusCode)
	}
}

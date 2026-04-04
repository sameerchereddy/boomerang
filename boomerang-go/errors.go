package boomerang

import "fmt"

// APIError is returned for any non-2xx response from the Boomerang API.
type APIError struct {
	StatusCode int
	Message    string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("boomerang: %d %s", e.StatusCode, e.Message)
}

// Is enables errors.Is matching by StatusCode, so callers can write:
//
//	errors.Is(err, boomerang.ErrUnauthorized)
func (e *APIError) Is(target error) bool {
	t, ok := target.(*APIError)
	if !ok {
		return false
	}
	return e.StatusCode == t.StatusCode
}

// Sentinel errors for the four expected Boomerang API error statuses.
var (
	ErrUnauthorized       = &APIError{StatusCode: 401} // missing or invalid JWT
	ErrForbidden          = &APIError{StatusCode: 403} // callbackUrl not in allowlist
	ErrConflict           = &APIError{StatusCode: 409} // duplicate job within cooldown
	ErrServiceUnavailable = &APIError{StatusCode: 503} // worker pool saturated
)

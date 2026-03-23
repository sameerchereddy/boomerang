package io.github.boomerang.model;

import io.github.boomerang.validation.ValidCallbackUrl;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Incoming request body for {@code POST /sync}. All fields are optional — when
 * {@code callbackUrl} is absent the caller must poll {@code GET /sync/{jobId}} for the
 * result instead.
 */
@Data
public class BoomerangRequest {

    /**
     * HTTPS URL to which Boomerang will POST the job result once processing completes.
     * Must be HTTPS and hosted on an allowed domain unless
     * {@code boomerang.callback.skip-validation} is {@code true}.
     */
    @ValidCallbackUrl
    private String callbackUrl;

    /**
     * Optional HMAC secret used to sign the callback payload. When present, Boomerang
     * adds an {@code X-Signature-SHA256} header to the outgoing webhook request.
     */
    @Nullable
    @Size(min = 32, max = 128)
    private String callbackSecret;

    /**
     * Caller-supplied idempotency key. When a key is re-used within the configured
     * cooldown window the second request is rejected with {@code 409 Conflict}.
     */
    @Nullable
    @Size(max = 128)
    private String idempotencyKey;
}

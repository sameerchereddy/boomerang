package io.github.boomerang.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.boomerang.validation.ValidCallbackUrl;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Incoming request body for {@code POST /jobs}. All fields are optional except where noted.
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

    /**
     * Arbitrary caller-supplied data. Stored alongside the job and surfaced in
     * {@link SyncContext#getPayload()} when the handler is invoked, allowing callers
     * to pass job-specific parameters without a separate lookup.
     *
     * <p>Example:
     * <pre>{@code
     * {
     *   "callbackUrl": "https://example.com/hook",
     *   "payload": { "userId": 42, "reportType": "monthly" }
     * }
     * }</pre>
     */
    @Nullable
    private JsonNode payload;

    /**
     * Optional schema version for the payload, e.g. {@code "v1"}, {@code "v2"}.
     * Stored with the job and surfaced in {@link SyncContext#getMessageVersion()} so
     * handlers can detect and adapt to schema changes mid-queue, avoiding the
     * "poisoned well" scenario where old messages are processed by code that no longer
     * understands their shape.
     */
    @Nullable
    @Size(max = 64)
    private String messageVersion;
}

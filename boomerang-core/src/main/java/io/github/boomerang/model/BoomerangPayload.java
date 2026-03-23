package io.github.boomerang.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Webhook callback payload sent to the caller's {@code callbackUrl} when a job completes
 * (successfully or with a failure). Serialised as JSON.
 */
@Data
@AllArgsConstructor
public class BoomerangPayload {

    /** Unique job identifier. */
    private String jobId;

    /** Terminal status of the job: {@code DONE} or {@code FAILED}. */
    private String status;

    /** Handler return value for {@code DONE} jobs; error message for {@code FAILED} jobs; may be {@code null}. */
    private Object result;

    /** Timestamp at which the job reached its terminal state. */
    private Instant completedAt;
}

package io.github.boomerang.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Lightweight status view returned by {@code GET /sync/{jobId}}. Does not include the
 * full job result payload — callers receive that via the webhook callback.
 */
@Data
@AllArgsConstructor
public class BoomerangJobStatus {

    /** Unique job identifier. */
    private String jobId;

    /** Current lifecycle status: {@code PENDING}, {@code IN_PROGRESS}, {@code DONE}, or {@code FAILED}. */
    private String status;

    /** Timestamp at which the job was originally enqueued. */
    private Instant createdAt;

    /** Timestamp at which the job transitioned to {@code DONE} or {@code FAILED}; {@code null} if still running. */
    private Instant completedAt;
}

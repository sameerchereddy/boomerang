package io.github.boomerang.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Immutable context object passed to the {@link io.github.boomerang.annotation.BoomerangHandler}
 * method when a job is being processed. Carries the job identifier, the authenticated caller
 * identifier, and the moment the job was picked up for processing.
 */
@Data
@AllArgsConstructor
public class SyncContext {

    /** Unique job identifier (UUID). */
    private final String jobId;

    /** JWT subject claim — identifies the authenticated caller that submitted the job. */
    private final String callerId;

    /** Timestamp at which the worker started processing this job. */
    private final Instant triggeredAt;
}

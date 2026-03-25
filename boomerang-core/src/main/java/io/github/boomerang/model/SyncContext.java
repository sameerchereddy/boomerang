package io.github.boomerang.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Immutable context object passed to the {@link io.github.boomerang.annotation.BoomerangHandler}
 * method when a job is being processed. Carries the job identifier, the authenticated caller
 * identifier, the moment the job was picked up for processing, and the optional caller-supplied
 * payload.
 */
@Data
@AllArgsConstructor
public class SyncContext {

    /** Typed job identifier. */
    private final JobId jobId;

    /** JWT subject claim — identifies the authenticated caller that submitted the job. */
    private final String callerId;

    /** Timestamp at which the worker started processing this job. */
    private final Instant triggeredAt;

    /**
     * Caller-supplied payload from {@link BoomerangRequest#getPayload()}, preserved as a
     * {@link JsonNode} so the handler can read individual fields or deserialise it to a
     * typed class using an {@code ObjectMapper}. {@code null} when the caller did not
     * include a payload.
     */
    @Nullable
    private final JsonNode payload;
}

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

    /**
     * Caller-supplied schema version from {@link BoomerangRequest#getMessageVersion()},
     * e.g. {@code "v1"}, {@code "v2"}. {@code null} when the caller did not include a
     * version. Handlers can inspect this to detect and adapt to payload schema changes
     * mid-queue, avoiding silent data corruption when an application is deployed while
     * jobs of an older schema are still queued (the "poisoned well" scenario).
     *
     * <p>Example:
     * <pre>{@code
     * String version = ctx.getMessageVersion(); // "v1", "v2", or null
     * if ("v2".equals(version)) {
     *     // handle new schema
     * } else {
     *     // handle legacy schema
     * }
     * }</pre>
     */
    @Nullable
    private final String messageVersion;
}

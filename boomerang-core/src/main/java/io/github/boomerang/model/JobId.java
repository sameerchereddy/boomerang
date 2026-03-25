package io.github.boomerang.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Objects;

/**
 * Value object wrapping a job identifier. Using a dedicated type rather than a raw
 * {@code String} makes handler and API signatures self-documenting and prevents
 * accidentally mixing job IDs with other string values.
 *
 * <p>Annotated with {@link JsonValue} so Jackson serialises it as a plain string
 * (e.g. {@code "550e8400-e29b-41d4-a716-446655440000"}) rather than as an object.
 */
public record JobId(@JsonValue String value) {

    public JobId {
        Objects.requireNonNull(value, "jobId must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

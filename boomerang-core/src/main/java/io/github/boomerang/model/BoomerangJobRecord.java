package io.github.boomerang.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Full job record as stored in Redis. Each field is persisted as a string value in a
 * Redis hash at key {@code boomerang-job:{jobId}}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BoomerangJobRecord {

    private String jobId;
    private String ownerId;
    private String lockKey;
    private String callbackUrl;
    private String callbackSecret;
    private String status;
    private Instant createdAt;
    private Instant completedAt;
    private String result;
    private String error;

    /** Raw JSON string of the caller-supplied payload, or {@code null} if none was provided. */
    private String payload;

    /** Caller-supplied schema version string, or {@code null} if not provided. */
    private String messageVersion;

    /**
     * Produces a lightweight {@link BoomerangJobStatus} view suitable for the status
     * polling endpoint.
     */
    public BoomerangJobStatus toStatusView() {
        return new BoomerangJobStatus(jobId, status, createdAt, completedAt);
    }

    /**
     * Reconstructs a {@link BoomerangJobRecord} from a raw Redis hash entry map. All
     * values are expected to be {@link String}; blank strings are treated as {@code null}
     * for nullable fields.
     *
     * @param jobId the job identifier (not stored redundantly in the hash)
     * @param data  raw hash entries from {@code opsForHash().entries(...)}
     * @return fully populated record
     */
    public static BoomerangJobRecord from(String jobId, Map<Object, Object> data) {
        BoomerangJobRecord record = new BoomerangJobRecord();
        record.setJobId(jobId);
        record.setOwnerId(getString(data, "ownerId"));
        record.setLockKey(getString(data, "lockKey"));
        record.setCallbackUrl(getString(data, "callbackUrl"));
        record.setCallbackSecret(getString(data, "callbackSecret"));
        record.setStatus(getString(data, "status"));

        String createdAtStr = getString(data, "createdAt");
        if (createdAtStr != null && !createdAtStr.isBlank()) {
            record.setCreatedAt(Instant.parse(createdAtStr));
        }

        String completedAtStr = getString(data, "completedAt");
        if (completedAtStr != null && !completedAtStr.isBlank()) {
            record.setCompletedAt(Instant.parse(completedAtStr));
        }

        String result = getString(data, "result");
        record.setResult((result != null && !result.isBlank()) ? result : null);

        String error = getString(data, "error");
        record.setError((error != null && !error.isBlank()) ? error : null);

        String payload = getString(data, "payload");
        record.setPayload((payload != null && !payload.isBlank()) ? payload : null);

        String messageVersion = getString(data, "messageVersion");
        record.setMessageVersion((messageVersion != null && !messageVersion.isBlank()) ? messageVersion : null);

        return record;
    }

    private static String getString(Map<Object, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : null;
    }
}

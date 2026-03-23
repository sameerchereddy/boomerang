package io.github.boomerang.store;

import io.github.boomerang.model.BoomerangJobRecord;
import io.github.boomerang.model.BoomerangRequest;

import java.util.Optional;

/**
 * Persistence contract for Boomerang job lifecycle management. Implementations are
 * responsible for durably storing job metadata and coordinating queue operations.
 *
 * <p>The default implementation backed by Redis is provided in {@code boomerang-redis}.
 * Alternative implementations (e.g. JDBC, in-memory for tests) may be registered as
 * Spring beans and will replace the default via {@code @ConditionalOnMissingBean}.
 */
public interface BoomerangJobStore {

    /**
     * Creates a new job record in the store and pushes the job identifier onto the
     * processing queue.
     *
     * @param jobId    globally unique job identifier (UUID)
     * @param callerId JWT subject identifying the authenticated caller
     * @param lockKey  Redis key used for idempotency / duplicate suppression
     * @param req      original request containing callback details
     */
    void enqueue(String jobId, String callerId, String lockKey, BoomerangRequest req);

    /**
     * Atomically updates the status of an existing job. Passing {@code null} for
     * {@code result} or {@code error} leaves those fields unchanged.
     *
     * @param jobId  job identifier
     * @param status new lifecycle status (e.g. {@code IN_PROGRESS}, {@code DONE}, {@code FAILED})
     * @param result serialisable result object for successful completions; may be {@code null}
     * @param error  human-readable error message for failures; may be {@code null}
     */
    void updateStatus(String jobId, String status, Object result, String error);

    /**
     * Retrieves the full job record by identifier.
     *
     * @param jobId job identifier
     * @return an {@link Optional} containing the record, or empty if not found
     */
    Optional<BoomerangJobRecord> findById(String jobId);
}

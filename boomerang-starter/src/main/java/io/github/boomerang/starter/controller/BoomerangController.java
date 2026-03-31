package io.github.boomerang.starter.controller;

import io.github.boomerang.model.BoomerangJobStatus;
import io.github.boomerang.model.BoomerangPayload;
import io.github.boomerang.model.BoomerangRequest;
import io.github.boomerang.redis.BoomerangFailedWebhookStore;
import io.github.boomerang.store.BoomerangJobStore;
import io.github.boomerang.starter.config.BoomerangProperties;
import io.github.boomerang.starter.metrics.BoomerangMetrics;
import io.github.boomerang.starter.service.BoomerangWebhookService;
import io.github.boomerang.starter.validation.BoomerangCallbackUrlValidator;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Exposes the Boomerang HTTP endpoints under the configurable base path
 * ({@code boomerang.base-path}, default {@code /jobs}):
 * <ul>
 *   <li>{@code POST   {basePath}}                          — accepts a job, returns {@code 202 Accepted} + jobId</li>
 *   <li>{@code GET    {basePath}/{jobId}}                  — polls job status (ownership enforced)</li>
 *   <li>{@code GET    {basePath}/failed-webhooks}          — lists dead-lettered webhook deliveries</li>
 *   <li>{@code POST   {basePath}/failed-webhooks/{jobId}/replay} — retries a dead-lettered webhook</li>
 *   <li>{@code DELETE {basePath}/failed-webhooks/{jobId}}  — discards a dead-lettered webhook</li>
 * </ul>
 *
 * <p>All endpoints require a valid JWT Bearer token.
 */
@Slf4j
@RestController
@RequestMapping("${boomerang.base-path:/jobs}")
public class BoomerangController {

    private static final String LOCK_PREFIX = "boomerang-lock:";

    private final BoomerangJobStore            jobStore;
    private final StringRedisTemplate          redisTemplate;
    private final BoomerangCallbackUrlValidator callbackUrlValidator;
    private final BoomerangProperties          properties;
    private final BoomerangMetrics             metrics;
    private final BoomerangFailedWebhookStore  failedWebhookStore;
    private final BoomerangWebhookService      webhookService;

    public BoomerangController(BoomerangJobStore jobStore,
                               StringRedisTemplate redisTemplate,
                               BoomerangCallbackUrlValidator callbackUrlValidator,
                               BoomerangProperties properties,
                               BoomerangMetrics metrics,
                               BoomerangFailedWebhookStore failedWebhookStore,
                               BoomerangWebhookService webhookService) {
        this.jobStore            = jobStore;
        this.redisTemplate       = redisTemplate;
        this.callbackUrlValidator = callbackUrlValidator;
        this.properties          = properties;
        this.metrics             = metrics;
        this.failedWebhookStore  = failedWebhookStore;
        this.webhookService      = webhookService;
    }

    /**
     * Accepts a sync job. Returns {@code 202 Accepted} with a {@code jobId} immediately.
     * The actual work runs asynchronously; the result is delivered to {@code callbackUrl}
     * when complete.
     *
     * @param req      validated request body
     * @param callerId JWT {@code sub} claim identifying the authenticated caller
     */
    @PostMapping
    public ResponseEntity<?> triggerSync(@Valid @RequestBody BoomerangRequest req,
                                         @AuthenticationPrincipal String callerId) {

        // 1. SSRF allowlist checks
        if (req.getCallbackUrl() != null && !callbackUrlValidator.isAllowed(req.getCallbackUrl())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Callback URL not in allowlist"));
        }
        if (req.getWorkerUrl() != null && !callbackUrlValidator.isAllowed(req.getWorkerUrl())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Worker URL not in allowlist"));
        }

        // 2. Acquire per-caller idempotency lock (SET NX EX)
        String idempotencyKey = req.getIdempotencyKey() != null ? req.getIdempotencyKey() : callerId;
        String lockKey        = LOCK_PREFIX + idempotencyKey;
        String jobId          = UUID.randomUUID().toString();

        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, jobId,
                        properties.getIdempotency().getCooldownSeconds(), TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(lockAcquired)) {
            Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
            metrics.idempotencyBlocks.increment();
            log.debug("Idempotency lock active for key {} — returning 409", lockKey);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", "Job already in progress or recently completed",
                            "retryAfterSeconds", ttl != null ? ttl : properties.getIdempotency().getCooldownSeconds()
                    ));
        }

        // 3. Enqueue job and return 202 immediately
        try {
            jobStore.enqueue(jobId, callerId, lockKey, req);
            metrics.jobsCreated.increment();
            log.info("Job {} enqueued for caller {}", jobId, callerId);
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));

        } catch (Exception e) {
            // Enqueue failed — release the lock so the caller can retry
            redisTemplate.delete(lockKey);
            log.error("Failed to enqueue job {} — lock released", jobId, e);
            throw e;
        }
    }

    /**
     * Returns the current status of a job. Ownership is enforced: only the caller that
     * created the job can poll it. Returns {@code 404} for unknown jobs and for jobs
     * owned by a different caller (avoids confirming existence to unauthorised callers).
     *
     * @param jobId    path variable identifying the job
     * @param callerId JWT {@code sub} claim of the authenticated caller
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<BoomerangJobStatus> getStatus(@PathVariable String jobId,
                                                         @AuthenticationPrincipal String callerId) {
        return jobStore.findById(jobId)
                .filter(job -> callerId.equals(job.getOwnerId()))
                .map(job -> ResponseEntity.ok(job.toStatusView()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Job not found: " + jobId));
    }

    // -------------------------------------------------------------------------
    // Failed-webhook management
    // -------------------------------------------------------------------------

    /**
     * Returns all webhook deliveries that have exhausted their retry budget. Each entry
     * includes the {@code jobId}, {@code callbackUrl}, serialised {@code payload},
     * {@code failedAt} timestamp, {@code lastError} message, and {@code attempts} count.
     */
    @GetMapping("/failed-webhooks")
    public ResponseEntity<List<Map<Object, Object>>> listFailedWebhooks() {
        return ResponseEntity.ok(failedWebhookStore.findAll());
    }

    /**
     * Re-attempts delivery of a dead-lettered webhook. If the delivery succeeds the
     * entry is removed from the store. If it fails again it remains for a future replay.
     *
     * @param jobId the job whose failed webhook should be replayed
     */
    @PostMapping("/failed-webhooks/{jobId}/replay")
    public ResponseEntity<?> replayFailedWebhook(@PathVariable String jobId) {
        Map<Object, Object> entry = failedWebhookStore.findByJobId(jobId);
        if (entry == null || entry.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No failed webhook found for job: " + jobId);
        }

        String url    = (String) entry.get("callbackUrl");
        String status = (String) entry.getOrDefault("status", "DONE");
        String payload = (String) entry.get("payload");

        log.info("Replaying webhook for job {} → {}", jobId, url);
        webhookService.fire(url, jobId, status, payload, null);

        // If we reached here without exception the delivery succeeded — purge the entry
        failedWebhookStore.delete(jobId);
        return ResponseEntity.ok(Map.of("replayed", true, "jobId", jobId));
    }

    /**
     * Discards a dead-lettered webhook entry without re-attempting delivery. Use when
     * the callback URL is permanently invalid and the entry should not be retried.
     *
     * @param jobId the job whose failed webhook record should be deleted
     */
    @DeleteMapping("/failed-webhooks/{jobId}")
    public ResponseEntity<Void> deleteFailedWebhook(@PathVariable String jobId) {
        failedWebhookStore.delete(jobId);
        log.info("Deleted failed webhook entry for job {}", jobId);
        return ResponseEntity.noContent().build();
    }
}

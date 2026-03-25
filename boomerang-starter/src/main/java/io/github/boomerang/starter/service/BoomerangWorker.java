package io.github.boomerang.starter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.boomerang.model.BoomerangJobRecord;
import io.github.boomerang.model.JobId;
import io.github.boomerang.model.SyncContext;
import io.github.boomerang.store.BoomerangJobStore;
import io.github.boomerang.starter.metrics.BoomerangMetrics;
import io.github.boomerang.starter.registry.BoomerangHandlerRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drives the async job processing loop. A single dedicated poller thread performs a
 * blocking BRPOP on the Redis pending queue (5 s timeout) and submits each popped job
 * to the {@code boomerangTaskExecutor} thread pool for processing.
 *
 * <p>If the thread pool is saturated the job identifier is pushed back to the right end
 * of the queue (preserving FIFO order) and the poller backs off for 1 second before
 * resuming. This prevents job loss when the executor rejects a submission.
 *
 * <p>The poller starts on {@link ContextRefreshedEvent} (Order 2, after
 * {@link BoomerangHandlerRegistry} scans at Order 1) and stops cleanly on
 * application shutdown via {@link #stop()}.
 */
@Slf4j
public class BoomerangWorker {

    private static final String PENDING_QUEUE = "boomerang-jobs:pending";
    private static final String JOB_PREFIX    = "boomerang-job:";

    private final StringRedisTemplate     redisTemplate;
    private final BoomerangJobStore       jobStore;
    private final BoomerangHandlerRegistry handlerRegistry;
    private final BoomerangWebhookService webhookService;
    private final BoomerangMetrics        metrics;
    private final ObjectMapper            objectMapper;
    private final Executor                taskExecutor;
    private final AtomicBoolean           running = new AtomicBoolean(false);

    public BoomerangWorker(StringRedisTemplate redisTemplate,
                           BoomerangJobStore jobStore,
                           BoomerangHandlerRegistry handlerRegistry,
                           BoomerangWebhookService webhookService,
                           BoomerangMetrics metrics,
                           @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
                           @Qualifier("boomerangTaskExecutor") Executor taskExecutor) {
        this.redisTemplate  = redisTemplate;
        this.jobStore       = jobStore;
        this.handlerRegistry = handlerRegistry;
        this.webhookService = webhookService;
        this.metrics        = metrics;
        this.objectMapper   = objectMapper;
        this.taskExecutor   = taskExecutor;
    }

    /**
     * Starts the polling loop after the application context is fully refreshed (Order 2).
     * Idempotent — only one poller thread is ever started.
     */
    @EventListener(ContextRefreshedEvent.class)
    @Order(2)
    public void startPolling() {
        if (running.compareAndSet(false, true)) {
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "boomerang-poller");
                t.setDaemon(false);
                return t;
            }).submit(this::pollLoop);
            log.info("Boomerang worker poller started");
        }
    }

    /** Signals the polling loop to exit on the next iteration. */
    public void stop() {
        running.set(false);
        log.info("Boomerang worker poller stopping");
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void pollLoop() {
        while (running.get()) {
            try {
                String jobId = redisTemplate.opsForList()
                        .rightPop(PENDING_QUEUE, Duration.ofSeconds(5));

                if (jobId == null) {
                    continue; // BRPOP timed out — loop and try again
                }

                try {
                    taskExecutor.execute(() -> processJob(jobId));
                } catch (RejectedExecutionException e) {
                    // Pool saturated — put the job back and back off
                    log.warn("Worker pool full, re-queuing job {}", jobId);
                    redisTemplate.opsForList().rightPush(PENDING_QUEUE, jobId);
                    metrics.poolRejections.increment();
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        running.set(false);
                    }
                }

            } catch (Exception e) {
                if (running.get()) {
                    log.error("Unexpected error in Boomerang poller loop", e);
                }
            }
        }
        log.info("Boomerang worker poller stopped");
    }

    private void processJob(String jobId) {
        log.debug("Processing job {}", jobId);
        Instant start = Instant.now();

        // Load full job record from Redis
        var jobOpt = jobStore.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.error("Job {} not found in store — skipping", jobId);
            return;
        }
        BoomerangJobRecord job = jobOpt.get();

        String lockKey     = job.getLockKey();
        String callbackUrl = job.getCallbackUrl();
        String secret      = job.getCallbackSecret();

        try {
            jobStore.updateStatus(jobId, "IN_PROGRESS", null, null);

            JsonNode payload = null;
            if (job.getPayload() != null && !job.getPayload().isBlank()) {
                try {
                    payload = objectMapper.readTree(job.getPayload());
                } catch (Exception e) {
                    log.warn("Could not parse payload JSON for job {} — proceeding with null payload: {}", jobId, e.getMessage());
                }
            }

            SyncContext ctx = new SyncContext(new JobId(jobId), job.getOwnerId(), Instant.now(), payload);
            Object result   = handlerRegistry.invoke(ctx);

            jobStore.updateStatus(jobId, "DONE", result, null);
            metrics.jobsCompleted.increment();
            metrics.jobDuration.record(Duration.between(start, Instant.now()));
            log.info("Job {} completed successfully", jobId);

            if (callbackUrl != null && !callbackUrl.isBlank()) {
                webhookService.fire(callbackUrl, jobId, "DONE", result, secret);
                metrics.webhookSuccesses.increment();
            }

        } catch (Exception e) {
            log.error("Job {} failed: {}", jobId, e.getMessage(), e);
            jobStore.updateStatus(jobId, "FAILED", null, e.getMessage());
            metrics.jobsFailed.increment();
            metrics.jobDuration.record(Duration.between(start, Instant.now()));

            if (callbackUrl != null && !callbackUrl.isBlank()) {
                webhookService.fire(callbackUrl, jobId, "FAILED", null, secret);
            }

        } finally {
            // Always release the idempotency lock so the caller can re-trigger
            if (lockKey != null && !lockKey.isBlank()) {
                redisTemplate.delete(lockKey);
                log.debug("Released idempotency lock {} for job {}", lockKey, jobId);
            }
        }
    }
}

package io.github.boomerang.starter.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Central Micrometer metrics bean for Boomerang. All counters and timers are registered
 * at construction time. The queue-depth gauge reads the Redis list length on every scrape.
 *
 * <p>Recommended Prometheus alert thresholds:
 * <ul>
 *   <li>{@code boomerang.queue.depth > 50} → warning; {@code > 200} → critical</li>
 *   <li>{@code boomerang.pool.rejections > 10/min} → critical</li>
 *   <li>{@code boomerang.webhook.failure rate > 5%} → warning</li>
 *   <li>{@code boomerang.job.duration P95 > 30s} → warning</li>
 * </ul>
 */
public class BoomerangMetrics {

    private static final String PENDING_QUEUE = "boomerang-jobs:pending";

    public final Counter jobsCreated;
    public final Counter jobsCompleted;
    public final Counter jobsFailed;
    public final Counter idempotencyBlocks;
    public final Counter webhookSuccesses;
    public final Counter webhookFailures;
    public final Counter poolRejections;
    public final Counter workerInvocations;
    public final Counter workerInvocationFailures;
    public final Timer   jobDuration;
    public final Timer   webhookDuration;

    public BoomerangMetrics(MeterRegistry registry, StringRedisTemplate redisTemplate) {
        jobsCreated = Counter.builder("boomerang.jobs.created")
                .description("Total number of jobs accepted and enqueued")
                .register(registry);

        jobsCompleted = Counter.builder("boomerang.jobs.completed")
                .description("Total number of jobs that completed successfully")
                .register(registry);

        jobsFailed = Counter.builder("boomerang.jobs.failed")
                .description("Total number of jobs that ended in FAILED state")
                .register(registry);

        idempotencyBlocks = Counter.builder("boomerang.idempotency.blocks")
                .description("Requests rejected by the per-caller idempotency lock (409 responses)")
                .register(registry);

        webhookSuccesses = Counter.builder("boomerang.webhook.success")
                .description("Webhook deliveries that succeeded within the retry budget")
                .register(registry);

        webhookFailures = Counter.builder("boomerang.webhook.failure")
                .description("Webhook deliveries that exhausted all retries and were dead-lettered")
                .register(registry);

        poolRejections = Counter.builder("boomerang.pool.rejections")
                .description("Jobs rejected by the worker thread pool and re-queued for later processing")
                .register(registry);

        workerInvocations = Counter.builder("boomerang.worker.invocations")
                .description("Standalone-mode jobs dispatched to a workerUrl")
                .register(registry);

        workerInvocationFailures = Counter.builder("boomerang.worker.invocation.failures")
                .description("Standalone-mode worker invocations that failed after all retries")
                .register(registry);

        jobDuration = Timer.builder("boomerang.job.duration")
                .description("Wall-clock time from job start to completion (success or failure)")
                .register(registry);

        webhookDuration = Timer.builder("boomerang.webhook.duration")
                .description("Time taken for a single webhook delivery attempt (excluding retries)")
                .register(registry);

        Gauge.builder("boomerang.queue.depth", redisTemplate, t -> {
                    Long size = t.opsForList().size(PENDING_QUEUE);
                    return size != null ? size.doubleValue() : 0.0;
                })
                .description("Number of jobs currently waiting in the pending queue")
                .register(registry);
    }
}

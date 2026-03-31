package io.github.boomerang.starter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Externalized configuration for Boomerang. All properties are namespaced under
 * {@code boomerang} in {@code application.yml} / {@code application.properties}.
 */
@Data
@ConfigurationProperties(prefix = "boomerang")
public class BoomerangProperties {

    private Auth        auth        = new Auth();
    private Callback    callback    = new Callback();
    private Idempotency idempotency = new Idempotency();
    private ThreadPool  threadPool  = new ThreadPool();
    private Webhook     webhook     = new Webhook();
    private Worker      worker      = new Worker();

    /**
     * Base URL path for all Boomerang endpoints. Override to match your domain language —
     * e.g. {@code /reports}, {@code /exports}, {@code /render-jobs}. Default: {@code /jobs}.
     */
    private String basePath = "/jobs";

    /** Number of days job metadata is retained in Redis. Default: 7. */
    private int jobTtlDays = 7;

    /** Number of days failed-webhook records are retained in Redis. Default: 30. */
    private int failedWebhookTtlDays = 30;

    @Data
    public static class Auth {
        /**
         * HS256 secret used to verify incoming JWT Bearer tokens. Must be at least 32
         * characters. Inject via environment variable — never hardcode in source.
         */
        private String jwtSecret;
    }

    @Data
    public static class Callback {
        /**
         * Domains that are accepted as {@code callbackUrl} hosts. Both exact-match and
         * subdomain-match are supported (e.g. {@code "example.com"} also accepts
         * {@code "api.example.com"}).
         */
        private List<String> allowedDomains = new ArrayList<>();

        /**
         * Optional stricter allowlist of full URL prefixes. When non-empty, the
         * {@code callbackUrl} must start with one of these values in addition to the
         * domain check.
         */
        private List<String> allowedUrls = new ArrayList<>();

        /**
         * When {@code true}, all SSRF-prevention checks on the {@code callbackUrl} are
         * bypassed. <strong>Only use in local development.</strong> Default: false.
         */
        private boolean skipValidation = false;
    }

    @Data
    public static class Idempotency {
        /**
         * How long (in seconds) a per-caller idempotency lock is held after a job is
         * enqueued. Duplicate requests within this window receive {@code 409 Conflict}.
         * Default: 300 (5 minutes).
         */
        private long cooldownSeconds = 300;
    }

    @Data
    public static class ThreadPool {
        /** Minimum number of threads kept alive in the worker pool. Default: 5. */
        private int coreSize = 5;

        /** Maximum number of threads in the worker pool. Default: 20. */
        private int maxSize = 20;

        /**
         * Number of jobs that may be queued waiting for a worker thread before the pool
         * begins rejecting submissions. Default: 100.
         */
        private int queueCapacity = 100;
    }

    @Data
    public static class Webhook {
        /**
         * Maximum number of delivery attempts before a webhook is dead-lettered.
         * Default: 5.
         */
        private int maxAttempts = 5;

        /**
         * Initial backoff interval in milliseconds between retry attempts. Doubles on
         * each subsequent attempt up to {@code maxBackoffMs}. Default: 1000.
         */
        private long initialBackoffMs = 1_000;

        /**
         * Maximum backoff interval in milliseconds. Default: 30000 (30 s).
         */
        private long maxBackoffMs = 30_000;
    }

    @Data
    public static class Worker {
        /**
         * Maximum number of HTTP call attempts to the {@code workerUrl} before the job
         * is marked {@code FAILED}. Default: 3.
         */
        private int maxAttempts = 3;

        /**
         * Per-attempt HTTP response timeout in seconds when calling the {@code workerUrl}.
         * Long-running jobs should increase this. Default: 300 (5 minutes).
         */
        private int timeoutSeconds = 300;

        /**
         * Maximum response body size in bytes accepted from the {@code workerUrl}.
         * Responses exceeding this limit cause the job to be marked {@code FAILED}.
         * Default: 10485760 (10 MB).
         */
        private int maxResponseSizeBytes = 10 * 1024 * 1024;
    }
}

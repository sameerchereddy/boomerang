package io.github.boomerang.starter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.boomerang.model.BoomerangPayload;
import io.github.boomerang.redis.BoomerangFailedWebhookStore;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Fires webhook callbacks to the consumer's {@code callbackUrl} once a job reaches a
 * terminal state. Uses an exponential-backoff {@link RetryTemplate} with configurable
 * attempts and backoff (see {@code boomerang.webhook.*}). Deliveries that exhaust all
 * retries are persisted in {@link BoomerangFailedWebhookStore} for manual inspection
 * and replay.
 *
 * <p>When a {@code callbackSecret} is present, every outgoing POST includes an
 * {@code X-Signature-SHA256: sha256=<hex>} header computed over the serialised JSON body
 * using HmacSHA256. Consumers should verify this header with a constant-time comparison
 * before processing the payload.
 */
@Slf4j
public class BoomerangWebhookService {

    private final RestClient                  restClient;
    private final BoomerangFailedWebhookStore failedWebhookStore;
    private final ObjectMapper                objectMapper;
    private final int                         maxAttempts;
    private final long                        initialBackoffMs;
    private final long                        maxBackoffMs;

    public BoomerangWebhookService(@Qualifier("boomerangRestClient") RestClient restClient,
                                   BoomerangFailedWebhookStore failedWebhookStore,
                                   @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
                                   int maxAttempts,
                                   long initialBackoffMs,
                                   long maxBackoffMs) {
        this.restClient       = restClient;
        this.failedWebhookStore = failedWebhookStore;
        this.objectMapper     = objectMapper;
        this.maxAttempts      = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs     = maxBackoffMs;
    }

    /**
     * Sends the job result to the caller's {@code callbackUrl}. Retries on failure
     * with exponential backoff up to {@code maxAttempts} before dead-lettering.
     *
     * @param url     the consumer's callback URL
     * @param jobId   job identifier included in the payload
     * @param status  terminal status ({@code DONE} or {@code FAILED})
     * @param result  result object for successful jobs; error message for failed jobs
     * @param secret  HMAC secret for payload signing; {@code null} to skip signing
     */
    public void fire(String url, String jobId, String status, Object result, String secret) {
        BoomerangPayload payload = new BoomerangPayload(jobId, status, result, Instant.now());
        String body = toJson(payload);

        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .exponentialBackoff(initialBackoffMs, 2.0, maxBackoffMs)
                .build();

        String signature = (secret != null && !secret.isBlank())
                ? "sha256=" + hmacSha256(body, secret)
                : null;

        try {
            retry.execute(ctx -> {
                if (ctx.getRetryCount() > 0) {
                    log.warn("Retrying webhook for job {} (attempt {})", jobId, ctx.getRetryCount() + 1);
                }

                RestClient.RequestBodySpec spec = restClient.post()
                        .uri(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);

                if (signature != null) {
                    spec = spec.header("X-Signature-SHA256", signature);
                }

                spec.retrieve().toBodilessEntity();
                return null;
            });

            log.debug("Webhook delivered successfully for job {}", jobId);

        } catch (Exception e) {
            log.error("Webhook permanently failed for job {} after all retries. Storing for replay.", jobId, e);
            failedWebhookStore.save(jobId, url, payload, e.getMessage());
        }
    }

    private String hmacSha256(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Hex.encodeHexString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize webhook payload to JSON", e);
        }
    }
}

package io.github.boomerang.starter.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Invokes the consumer's {@code workerUrl} endpoint in standalone mode. Boomerang POSTs
 * the job payload to the URL, captures the response body as the job result, and returns
 * it to the caller. Retries on failure using a fixed-delay {@link RetryTemplate}.
 *
 * <p>The outgoing request follows the Boomerang worker invocation contract:
 * <ul>
 *   <li>{@code X-Boomerang-Job-Id} — the job identifier</li>
 *   <li>{@code X-Signature-SHA256} — HmacSHA256 signature over the body (only when a
 *       {@code callbackSecret} was provided with the original request)</li>
 *   <li>{@code Content-Type: application/json}</li>
 *   <li>Body: {@code { "jobId": "...", "triggeredAt": "..." }}</li>
 * </ul>
 *
 * <p>Any 2xx response is treated as success; the response body is returned as the result.
 * Any non-2xx causes a {@link WorkerInvocationException}, which {@link BoomerangWorker}
 * catches and uses to mark the job {@code FAILED}.
 */
@Slf4j
public class StandaloneWorkerInvoker {

    private final RestClient   restClient;
    private final ObjectMapper objectMapper;
    private final int          maxAttempts;
    private final int          maxResponseSizeBytes;

    public StandaloneWorkerInvoker(@Qualifier("boomerangWorkerRestClient") RestClient restClient,
                                   @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
                                   int maxAttempts,
                                   int maxResponseSizeBytes) {
        this.restClient          = restClient;
        this.objectMapper        = objectMapper;
        this.maxAttempts         = maxAttempts;
        this.maxResponseSizeBytes = maxResponseSizeBytes;
    }

    /**
     * Calls the {@code workerUrl} and returns the response body as a String.
     *
     * @param workerUrl     consumer's worker endpoint
     * @param jobId         job identifier
     * @param secret        HMAC secret for signing; {@code null} to skip signing
     * @param triggeredAt   job trigger timestamp
     * @return response body from the worker (used as job result)
     * @throws WorkerInvocationException if all attempts are exhausted or the response is too large
     */
    public String invoke(String workerUrl, String jobId, String secret, Instant triggeredAt) {
        String body = buildRequestBody(jobId, triggeredAt);

        String signature = (secret != null && !secret.isBlank())
                ? BoomerangHmacUtils.sign(body, secret)
                : null;

        RetryTemplate retry = RetryTemplate.builder()
                .maxAttempts(maxAttempts)
                .fixedBackoff(2_000)
                .build();

        try {
            return retry.execute(ctx -> {
                if (ctx.getRetryCount() > 0) {
                    log.warn("Retrying worker invocation for job {} (attempt {})", jobId, ctx.getRetryCount() + 1);
                }

                RestClient.RequestBodySpec spec = restClient.post()
                        .uri(workerUrl)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Boomerang-Job-Id", jobId)
                        .body(body);

                if (signature != null) {
                    spec = spec.header("X-Signature-SHA256", signature);
                }

                String responseBody = spec.retrieve().body(String.class);

                if (responseBody != null && responseBody.length() > maxResponseSizeBytes) {
                    throw new WorkerInvocationException(
                            "Worker response exceeds max size of " + maxResponseSizeBytes + " bytes for job " + jobId);
                }

                return responseBody;
            });

        } catch (WorkerInvocationException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkerInvocationException(
                    "Worker invocation failed for job " + jobId + " after " + maxAttempts + " attempt(s): " + e.getMessage(), e);
        }
    }

    private String buildRequestBody(String jobId, Instant triggeredAt) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("triggeredAt", triggeredAt.toString());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize worker request body", e);
        }
    }

    /**
     * Thrown when the worker endpoint cannot be reached or returns a non-2xx response
     * after all retry attempts, or when the response body exceeds the configured size limit.
     */
    public static class WorkerInvocationException extends RuntimeException {
        public WorkerInvocationException(String message) {
            super(message);
        }
        public WorkerInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

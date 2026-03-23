package io.github.boomerang.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Persists webhook deliveries that have exhausted all retry attempts. Each failed delivery
 * is stored as a Redis Hash at key {@code boomerang-failed-webhook:{jobId}} with a
 * configurable TTL (default 30 days). A scheduled replay job may call {@link #findAll()}
 * and {@link #delete(String)} once the consumer endpoint recovers.
 *
 * <p>Uses {@code SCAN} (not {@code KEYS}) to enumerate failed entries so that large
 * keyspaces do not block the Redis event loop.
 */
@Slf4j
public class BoomerangFailedWebhookStore {

    private static final String FAILED_PREFIX = "boomerang-failed-webhook:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;
    private final int                 ttlDays;

    public BoomerangFailedWebhookStore(StringRedisTemplate redisTemplate,
                                       @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
                                       @Value("${boomerang.failed-webhook-ttl-days:30}") int ttlDays) {
        this.redisTemplate = redisTemplate;
        this.objectMapper  = objectMapper;
        this.ttlDays       = ttlDays;
    }

    /**
     * Persists a failed webhook delivery so it can be inspected and replayed later.
     */
    public void save(String jobId, String url, Object payload, String lastError) {
        String key = FAILED_PREFIX + jobId;
        Map<String, String> entry = new HashMap<>();
        entry.put("jobId",       jobId);
        entry.put("callbackUrl", url);
        entry.put("payload",     toJson(payload));
        entry.put("failedAt",    Instant.now().toString());
        entry.put("lastError",   lastError != null ? lastError : "unknown");
        entry.put("attempts",    "5");

        redisTemplate.opsForHash().putAll(key, entry);
        redisTemplate.expire(key, ttlDays, TimeUnit.DAYS);

        log.error("Webhook permanently failed for job {} — stored for manual replay. URL={}, error={}",
                jobId, url, lastError);
    }

    /**
     * Returns all persisted failed-webhook entries. Uses a cursor-based SCAN to avoid
     * blocking Redis.
     */
    public List<Map<Object, Object>> findAll() {
        List<Map<Object, Object>> results = new ArrayList<>();
        ScanOptions opts = ScanOptions.scanOptions()
                .match(FAILED_PREFIX + "*")
                .count(100)
                .build();
        try (Cursor<String> cursor = redisTemplate.scan(opts)) {
            cursor.forEachRemaining(key ->
                    results.add(redisTemplate.opsForHash().entries(key)));
        }
        return results;
    }

    /**
     * Removes a failed-webhook entry after a successful replay.
     */
    /**
     * Returns the failed-webhook entry for a single job, or an empty map if not found.
     */
    public Map<Object, Object> findByJobId(String jobId) {
        return redisTemplate.opsForHash().entries(FAILED_PREFIX + jobId);
    }

    /**
     * Removes a failed-webhook entry after a successful replay.
     */
    public void delete(String jobId) {
        redisTemplate.delete(FAILED_PREFIX + jobId);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize failed-webhook payload to JSON", e);
        }
    }
}

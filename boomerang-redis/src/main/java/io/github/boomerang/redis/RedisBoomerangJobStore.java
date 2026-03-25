package io.github.boomerang.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.boomerang.model.BoomerangJobRecord;
import io.github.boomerang.model.BoomerangRequest;
import io.github.boomerang.store.BoomerangJobStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed implementation of {@link BoomerangJobStore}. Job metadata is persisted as a
 * Redis Hash at key {@code boomerang-job:{jobId}} and the job identifier is pushed onto the
 * Redis List at key {@code boomerang-jobs:pending} so the worker poller can pick it up.
 */
public class RedisBoomerangJobStore implements BoomerangJobStore {

    static final String PENDING_QUEUE = "boomerang-jobs:pending";
    static final String JOB_PREFIX    = "boomerang-job:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;
    private final int                 jobTtlDays;

    public RedisBoomerangJobStore(StringRedisTemplate redisTemplate,
                                  @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
                                  @Value("${boomerang.job-ttl-days:7}") int jobTtlDays) {
        this.redisTemplate = redisTemplate;
        this.objectMapper  = objectMapper;
        this.jobTtlDays    = jobTtlDays;
    }

    @Override
    public void enqueue(String jobId, String callerId, String lockKey, BoomerangRequest req) {
        Map<String, String> jobData = new HashMap<>();
        jobData.put("status",         "PENDING");
        jobData.put("ownerId",        callerId);
        jobData.put("lockKey",        lockKey);
        jobData.put("callbackUrl",    req.getCallbackUrl()    != null ? req.getCallbackUrl()    : "");
        jobData.put("callbackSecret", req.getCallbackSecret() != null ? req.getCallbackSecret() : "");
        jobData.put("createdAt",      Instant.now().toString());
        jobData.put("completedAt",    "");
        jobData.put("result",         "");
        jobData.put("error",          "");
        jobData.put("payload",        req.getPayload()        != null ? req.getPayload().toString()        : "");
        jobData.put("messageVersion", req.getMessageVersion() != null ? req.getMessageVersion()             : "");

        String key = JOB_PREFIX + jobId;
        redisTemplate.opsForHash().putAll(key, jobData);
        redisTemplate.expire(key, jobTtlDays, TimeUnit.DAYS);
        redisTemplate.opsForList().leftPush(PENDING_QUEUE, jobId);
    }

    @Override
    public void updateStatus(String jobId, String status, Object result, String error) {
        String key = JOB_PREFIX + jobId;
        redisTemplate.opsForHash().put(key, "status",      status);
        redisTemplate.opsForHash().put(key, "completedAt", Instant.now().toString());
        if (result != null) {
            redisTemplate.opsForHash().put(key, "result", toJson(result));
        }
        if (error != null) {
            redisTemplate.opsForHash().put(key, "error", error);
        }
    }

    @Override
    public Optional<BoomerangJobRecord> findById(String jobId) {
        Map<Object, Object> data = redisTemplate.opsForHash().entries(JOB_PREFIX + jobId);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(BoomerangJobRecord.from(jobId, data));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize value to JSON", e);
        }
    }
}

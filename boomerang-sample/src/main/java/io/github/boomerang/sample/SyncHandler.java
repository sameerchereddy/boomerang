package io.github.boomerang.sample;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.boomerang.annotation.BoomerangHandler;
import io.github.boomerang.model.SyncContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Sample Boomerang handler. Simulates a long-running sync operation (3-second sleep)
 * and returns a simple result map. In a real application this would call external APIs,
 * diff data, and write deltas to the local store.
 *
 * <p>The caller can pass arbitrary data via the {@code payload} field of the request:
 * <pre>{@code
 * {
 *   "callbackUrl": "https://example.com/hook",
 *   "payload": { "dataSource": "crm", "since": "2026-01-01" }
 * }
 * }</pre>
 */
@Slf4j
@Component
public class SyncHandler {

    @BoomerangHandler
    public Map<String, Object> doSync(SyncContext ctx) throws InterruptedException {
        log.info("Starting sync for job={} caller={}", ctx.getJobId(), ctx.getCallerId());

        // Read caller-supplied payload fields if present
        JsonNode payload = ctx.getPayload();
        String dataSource = (payload != null && payload.has("dataSource"))
                ? payload.get("dataSource").asText()
                : "default";

        // Simulate slow work — replace with your real sync logic
        Thread.sleep(3_000);

        Map<String, Object> result = Map.of(
                "jobId",         ctx.getJobId(),
                "callerId",      ctx.getCallerId(),
                "dataSource",    dataSource,
                "syncedAt",      Instant.now().toString(),
                "recordsSynced", 42
        );

        log.info("Sync complete for job={}", ctx.getJobId());
        return result;
    }
}

package io.github.boomerang.sample;

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
 */
@Slf4j
@Component
public class SyncHandler {

    /**
     * The annotated method must accept a single {@link SyncContext} parameter. The return
     * value is serialised to JSON and delivered to the caller's {@code callbackUrl}.
     */
    @BoomerangHandler
    public Map<String, Object> doSync(SyncContext ctx) throws InterruptedException {
        log.info("Starting sync for job={} caller={}", ctx.getJobId(), ctx.getCallerId());

        // Simulate slow work — replace with your real sync logic
        Thread.sleep(3_000);

        Map<String, Object> result = Map.of(
                "jobId",      ctx.getJobId(),
                "callerId",   ctx.getCallerId(),
                "syncedAt",   Instant.now().toString(),
                "recordsSynced", 42
        );

        log.info("Sync complete for job={}", ctx.getJobId());
        return result;
    }
}

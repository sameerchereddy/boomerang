package io.github.boomerang.tests.app;

import io.github.boomerang.annotation.BoomerangHandler;
import io.github.boomerang.model.SyncContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * Test handler that echoes back job metadata. Sleeps 500 ms to simulate
 * async work without slowing the test suite down.
 */
@Component
public class EchoSyncHandler {

    @BoomerangHandler
    public Map<String, Object> handle(SyncContext ctx) throws InterruptedException {
        Thread.sleep(500);
        return Map.of(
                "jobId",     ctx.getJobId().toString(),
                "callerId",  ctx.getCallerId(),
                "echoedAt",  Instant.now().toString()
        );
    }
}

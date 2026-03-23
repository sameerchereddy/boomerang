package io.github.boomerang.tests;

import io.github.boomerang.tests.app.TestSyncApp;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concrete integration tests for the Boomerang starter. Demonstrates the intended usage
 * of {@link BoomerangIntegrationTestBase}:
 *
 * <ul>
 *   <li>Inherit Redis (Testcontainers) and WireMock infrastructure</li>
 *   <li>Use {@code generateToken} / {@code generateExpiredToken} for JWT Bearer headers</li>
 *   <li>Use {@code stubCallbackUrl} / {@code stubCallbackUrlWithInitialFailures} for
 *       callback endpoint stubs</li>
 *   <li>Use {@code verifyCallbackReceived} to assert delivery</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = TestSyncApp.class
)
class BoomerangSyncIT extends BoomerangIntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    // -------------------------------------------------------------------------
    // Auth
    // -------------------------------------------------------------------------

    @Test
    void noAuthHeaderReturns401() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/jobs",
                new HttpEntity<>("{\"callbackUrl\":\"http://localhost/x\"}", jsonHeaders(null)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenReturns401() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/jobs",
                new HttpEntity<>("{\"callbackUrl\":\"http://localhost/x\"}",
                        jsonHeaders(generateExpiredToken("user-x"))),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Trigger
    // -------------------------------------------------------------------------

    @Test
    void triggerReturnsTwoHundredTwoWithJobId() {
        stubCallbackUrl("/hooks/trigger");

        ResponseEntity<Map> resp = rest.postForEntity(
                "/jobs",
                new HttpEntity<>(body("/hooks/trigger"), jsonHeaders(generateToken("user-trigger"))),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat((String) resp.getBody().get("jobId")).isNotBlank();
    }

    // -------------------------------------------------------------------------
    // Callback delivery
    // -------------------------------------------------------------------------

    @Test
    void callbackDeliveredAfterJobCompletes() {
        stubCallbackUrl("/hooks/delivered");

        rest.postForEntity(
                "/jobs",
                new HttpEntity<>(body("/hooks/delivered"), jsonHeaders(generateToken("user-delivered"))),
                Map.class);

        // Handler sleeps 500 ms — allow up to 10 s
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> verifyCallbackReceived("/hooks/delivered"));
    }

    @Test
    void callbackDeliveredEvenAfterInitialFailures() {
        // Simulate the consumer endpoint returning 503 twice before recovering
        stubCallbackUrlWithInitialFailures("/hooks/flaky", 2);

        rest.postForEntity(
                "/jobs",
                new HttpEntity<>(body("/hooks/flaky"), jsonHeaders(generateToken("user-flaky"))),
                Map.class);

        // Two 503s + one success = at least 3 attempts; allow up to 15 s for backoff
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> verifyCallbackReceived("/hooks/flaky"));
    }

    // -------------------------------------------------------------------------
    // Status polling
    // -------------------------------------------------------------------------

    @Test
    void getStatusReturnsJobForOwner() {
        stubCallbackUrl("/hooks/status");
        String token = generateToken("user-status");

        ResponseEntity<Map> trigger = rest.postForEntity(
                "/jobs",
                new HttpEntity<>(body("/hooks/status"), jsonHeaders(token)),
                Map.class);

        String jobId = (String) trigger.getBody().get("jobId");

        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    ResponseEntity<Map> status = rest.exchange(
                            "/jobs/" + jobId,
                            HttpMethod.GET,
                            new HttpEntity<>(jsonHeaders(token)),
                            Map.class);
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(status.getBody().get("jobId")).isEqualTo(jobId);
                    assertThat(status.getBody().get("status")).isIn("PENDING", "IN_PROGRESS", "DONE");
                });
    }

    @Test
    void getStatusReturns404ForDifferentOwner() {
        stubCallbackUrl("/hooks/owner");

        ResponseEntity<Map> trigger = rest.postForEntity(
                "/jobs",
                new HttpEntity<>(body("/hooks/owner"), jsonHeaders(generateToken("owner"))),
                Map.class);

        String jobId = (String) trigger.getBody().get("jobId");

        ResponseEntity<String> status = rest.exchange(
                "/jobs/" + jobId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(generateToken("intruder"))),
                String.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    @Test
    void secondRequestFromSameCallerReturnsConflict() {
        stubCallbackUrl("/hooks/idem");
        // Unique subject per test run to avoid lock collisions
        String token = generateToken("idem-" + System.nanoTime());
        HttpEntity<String> req = new HttpEntity<>(body("/hooks/idem"), jsonHeaders(token));

        assertThat(rest.postForEntity("/jobs", req, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        assertThat(rest.postForEntity("/jobs", req, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String body(String hookPath) {
        return "{\"callbackUrl\":\"" + wireMock.baseUrl() + hookPath + "\"}";
    }

    private HttpHeaders jsonHeaders(String bearerToken) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            h.setBearerAuth(bearerToken);
        }
        return h;
    }
}

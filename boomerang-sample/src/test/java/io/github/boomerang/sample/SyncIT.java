package io.github.boomerang.sample;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Boomerang sample application.
 *
 * <p>Connects to a locally-running Redis instance (localhost:6379) — no Docker required.
 *
 * <p>A single WireMock server runs for the entire test class ({@code @BeforeAll}) so that
 * background webhook-delivery threads from earlier tests can always reach it. Stubs are reset
 * between tests via {@code @BeforeEach}.  A catch-all stub ensures any unmatched callback
 * still returns 200 rather than triggering slow retry back-off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                classes = SampleApp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyncIT {

    /** JWT secret must match the value in application.yml. */
    private static final String JWT_SECRET = "boomerang-dev-secret-key-min-32-chars!!";

    /** Single WireMock instance shared across all tests in this class. */
    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        // Reset WireMock stubs and request log
        wireMock.resetAll();

        // Catch-all: any unmatched POST returns 200 so background jobs don't retry forever
        wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(200)));

        // Flush all Boomerang keys so each test starts with a clean state
        Set<String> keys = redisTemplate.keys("boomerang*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    // -------------------------------------------------------------------------
    // Auth tests — no job enqueued, fast
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    void noTokenReturns401() {
        ResponseEntity<String> resp = rest.postForEntity(
                "/jobs",
                new HttpEntity<>("{\"callbackUrl\":\"" + wireMock.baseUrl() + "/ignored\"}",
                        jsonHeaders(null)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @Order(2)
    void expiredTokenReturns401() {
        String expiredToken = Jwts.builder()
                .subject("user-expired")
                .issuedAt(Date.from(Instant.now().minusSeconds(3610)))
                .expiration(Date.from(Instant.now().minusSeconds(10)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        ResponseEntity<String> resp = rest.postForEntity(
                "/jobs",
                new HttpEntity<>("{\"callbackUrl\":\"" + wireMock.baseUrl() + "/ignored\"}",
                        jsonHeaders(expiredToken)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------
    // Happy-path tests
    // -------------------------------------------------------------------------

    @Test
    @Order(3)
    void triggerReturnsTwoHundredTwoWithJobId() {
        String token = generateToken("user-trigger-test");
        String body = "{\"callbackUrl\":\"" + wireMock.baseUrl() + "/hooks/done\"}";

        ResponseEntity<Map> resp = rest.postForEntity(
                "/jobs",
                new HttpEntity<>(body, jsonHeaders(token)),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(resp.getBody()).containsKey("jobId");
        assertThat((String) resp.getBody().get("jobId")).isNotBlank();
    }

    @Test
    @Order(4)
    void callbackDeliveredAfterJobCompletes() {
        // Register a specific stub so we can verify exactly one hit on this path
        wireMock.stubFor(post(urlEqualTo("/hooks/callback"))
                .willReturn(aResponse().withStatus(200)));

        String token = generateToken("user-callback-test");
        String body = "{\"callbackUrl\":\"" + wireMock.baseUrl() + "/hooks/callback\"}";

        rest.postForEntity("/jobs",
                new HttpEntity<>(body, jsonHeaders(token)),
                Map.class);

        // Handler sleeps 3 s — allow up to 20 s for the callback to arrive
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .untilAsserted(() ->
                        wireMock.verify(1, postRequestedFor(urlEqualTo("/hooks/callback"))));
    }

    @Test
    @Order(5)
    void getStatusReturnsJobForOwner() {
        String token = generateToken("user-status-test");
        String body = "{\"callbackUrl\":\"" + wireMock.baseUrl() + "/hooks/status\"}";

        ResponseEntity<Map> trigger = rest.postForEntity(
                "/jobs",
                new HttpEntity<>(body, jsonHeaders(token)),
                Map.class);

        String jobId = (String) trigger.getBody().get("jobId");
        assertThat(jobId).isNotBlank();

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
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
    @Order(6)
    void getStatusReturns404ForDifferentOwner() {
        String ownerToken = generateToken("owner-user");
        String otherToken  = generateToken("other-user");
        String body = "{\"callbackUrl\":\"" + wireMock.baseUrl() + "/hooks/owner\"}";

        ResponseEntity<Map> trigger = rest.postForEntity(
                "/jobs",
                new HttpEntity<>(body, jsonHeaders(ownerToken)),
                Map.class);

        String jobId = (String) trigger.getBody().get("jobId");

        ResponseEntity<String> status = rest.exchange(
                "/jobs/" + jobId,
                HttpMethod.GET,
                new HttpEntity<>(jsonHeaders(otherToken)),
                String.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @Order(7)
    void idempotencyBlocksSecondRequest() {
        // Unique subject per test run to avoid clashes
        String token = generateToken("user-idem-" + System.nanoTime());
        String body  = "{\"callbackUrl\":\"" + wireMock.baseUrl() + "/hooks/idem\"}";
        HttpEntity<String> request = new HttpEntity<>(body, jsonHeaders(token));

        ResponseEntity<Map> first = rest.postForEntity("/jobs", request, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> second = rest.postForEntity("/jobs", request, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpHeaders jsonHeaders(String bearerToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return headers;
    }

    private String generateToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}

package io.github.boomerang.tests;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.redis.testcontainers.RedisContainer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base class for Boomerang integration tests. Provides:
 * <ul>
 *   <li>A real Redis instance via Testcontainers ({@link RedisContainer})</li>
 *   <li>A WireMock server for simulating {@code callbackUrl} and {@code workerUrl}
 *       endpoints</li>
 *   <li>JWT generation helpers so sub-classes can produce valid Bearer tokens</li>
 * </ul>
 *
 * <p>Extend this class and annotate the subclass with {@code @SpringBootTest} pointing at
 * the application under test. The Redis host/port are injected dynamically so Boomerang
 * connects to the container, not a real Redis instance.
 *
 * <pre>{@code
 * @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
 *                 classes = MyApp.class)
 * class MySyncTest extends BoomerangIntegrationTestBase {
 *
 *     @Test
 *     void triggerReturnsTwoHundredTwo() {
 *         stubCallbackUrl("/hooks/done");
 *
 *         given()
 *             .port(port)
 *             .header("Authorization", "Bearer " + generateToken("user-1"))
 *             .contentType("application/json")
 *             .body("{\"callbackUrl\":\"" + wireMock.baseUrl() + "/hooks/done\"}")
 *         .when()
 *             .post("/jobs")
 *         .then()
 *             .statusCode(202)
 *             .body("jobId", not(emptyString()));
 *     }
 * }
 * }</pre>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BoomerangIntegrationTestBase {

    /** Shared Redis container reused across all test methods in the same test class. */
    @Container
    static final RedisContainer REDIS = new RedisContainer(
            RedisContainer.DEFAULT_IMAGE_NAME.withTag("7-alpine"));

    /**
     * Injects the Testcontainers Redis host and port into the Spring application context
     * before any beans are created.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
    }

    /** The random port chosen by Spring Boot for the test server. */
    @LocalServerPort
    protected int port;

    /** WireMock server for stubbing {@code callbackUrl} and {@code workerUrl} endpoints. */
    protected WireMockServer wireMock;

    /** JWT secret injected from the application context — must match {@code boomerang.auth.jwt-secret}. */
    protected static final String TEST_JWT_SECRET =
            "boomerang-test-secret-key-min-32-chars!!";

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void stopWireMock() {
        if (wireMock != null && wireMock.isRunning()) {
            wireMock.stop();
        }
    }

    // -------------------------------------------------------------------------
    // WireMock helpers
    // -------------------------------------------------------------------------

    /**
     * Stubs the WireMock server to accept any POST to the given path and respond with
     * {@code 200 OK}. Use for callback URL stubs.
     *
     * @param path URL path, e.g. {@code "/hooks/done"}
     */
    protected void stubCallbackUrl(String path) {
        wireMock.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200)));
    }

    /**
     * Stubs the WireMock server to simulate a temporarily unavailable endpoint (returns
     * {@code 503} for the first {@code failTimes} requests, then {@code 200}).
     *
     * @param path      URL path
     * @param failTimes number of initial failures before the stub starts succeeding
     */
    protected void stubCallbackUrlWithInitialFailures(String path, int failTimes) {
        wireMock.stubFor(post(urlEqualTo(path))
                .inScenario("retries")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo(failTimes <= 1 ? "Recovered" : "Failing-" + (failTimes - 1)));

        for (int i = failTimes - 1; i > 0; i--) {
            String from = "Failing-" + i;
            String to   = i == 1 ? "Recovered" : "Failing-" + (i - 1);
            wireMock.stubFor(post(urlEqualTo(path))
                    .inScenario("retries")
                    .whenScenarioStateIs(from)
                    .willReturn(aResponse().withStatus(503))
                    .willSetStateTo(to));
        }

        wireMock.stubFor(post(urlEqualTo(path))
                .inScenario("retries")
                .whenScenarioStateIs("Recovered")
                .willReturn(aResponse().withStatus(200)));
    }

    /**
     * Verifies that the callback URL received exactly one POST request.
     *
     * @param path URL path that was stubbed
     */
    protected void verifyCallbackReceived(String path) {
        wireMock.verify(1, postRequestedFor(urlEqualTo(path)));
    }

    // -------------------------------------------------------------------------
    // JWT helpers
    // -------------------------------------------------------------------------

    /**
     * Generates a valid HS256 JWT with the given subject ({@code sub} claim) and a
     * 1-hour expiry. Uses the same secret as the test application context.
     *
     * @param subject the caller identity (maps to {@code callerId} in handlers)
     * @return a signed JWT string suitable for use in {@code Authorization: Bearer} headers
     */
    protected String generateToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /**
     * Generates a JWT that expired 10 seconds ago — useful for testing 401 responses.
     *
     * @param subject the caller identity
     * @return an expired signed JWT string
     */
    protected String generateExpiredToken(String subject) {
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(Instant.now().minusSeconds(3610)))
                .expiration(Date.from(Instant.now().minusSeconds(10)))
                .signWith(Keys.hmacShaKeyFor(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}

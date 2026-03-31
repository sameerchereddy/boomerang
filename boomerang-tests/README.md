# boomerang-tests

Integration test support library for applications built on Boomerang. Provides a JUnit 5 base class that spins up a containerised Redis (via Testcontainers), a WireMock server, and JWT generation helpers — so you can test the full async job lifecycle without mocking internals.

## Adding it

```xml
<dependency>
    <groupId>io.github.sameerchereddy</groupId>
    <artifactId>boomerang-tests</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

## Usage

Extend `BoomerangIntegrationTestBase` and annotate with `@SpringBootTest` pointing at your application class:

```java
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = MyApp.class
)
class MySyncTest extends BoomerangIntegrationTestBase {

    @Autowired TestRestTemplate rest;

    @Test
    void triggerReturnsTwoHundredTwo() {
        stubCallbackUrl("/hooks/done");

        ResponseEntity<Map> resp = rest.postForEntity(
            "/jobs",
            new HttpEntity<>(
                "{\"callbackUrl\":\"" + wireMock.baseUrl() + "/hooks/done\"}",
                headers(generateToken("user-1"))
            ),
            Map.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat((String) resp.getBody().get("jobId")).isNotBlank();
    }

    @Test
    void callbackDeliveredAfterJobCompletes() {
        stubCallbackUrl("/hooks/done");

        rest.postForEntity("/jobs", /* ... */, Map.class);

        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> verifyCallbackReceived("/hooks/done"));
    }
}
```

## What the base class provides

### Infrastructure

| Field | Type | Description |
|-------|------|-------------|
| `REDIS` | `RedisContainer` | Shared Redis container started once per test class |
| `wireMock` | `WireMockServer` | Per-test WireMock server on a random port |
| `port` | `int` | Random port the Spring Boot test server listens on |

Redis host/port are injected into the Spring context via `@DynamicPropertySource` before any beans are created.

### WireMock helpers

**Callback (embedded mode)**

| Method | Description |
|--------|-------------|
| `stubCallbackUrl(path)` | Stubs a POST to `path` — returns `200 OK` |
| `stubCallbackUrlWithInitialFailures(path, n)` | Returns `503` for the first `n` calls, then `200` — useful for testing retry logic |
| `verifyCallbackReceived(path)` | Asserts exactly one POST was received at `path` |

**Worker (standalone mode)**

| Method | Description |
|--------|-------------|
| `stubWorkerUrl(path)` | Stubs a POST to `path` — returns `200 OK` with `{"workerResult":"ok"}` |
| `stubWorkerUrlWithFailure(path)` | Returns `500` on every call — triggers job `FAILED` path |
| `verifyWorkerCalledWithJobId(path, jobId)` | Asserts worker was called once with matching `X-Boomerang-Job-Id` header |
| `verifyWorkerSignaturePresent(path)` | Asserts `X-Signature-SHA256: sha256=<hex>` header was present |

### JWT helpers

| Method | Description |
|--------|-------------|
| `generateToken(subject)` | Valid HS256 token, 1-hour expiry |
| `generateExpiredToken(subject)` | Expired token — useful for testing 401 responses |

The JWT secret used is `"boomerang-test-secret-key-min-32-chars!!"`. Your test `application.yml` must set `boomerang.auth.jwt-secret` to the same value.

## Test application.yml

Minimal config for a test application using this base class:

```yaml
boomerang:
  auth:
    jwt-secret: boomerang-test-secret-key-min-32-chars!!
  callback:
    skip-validation: true       # WireMock runs on localhost
  idempotency:
    cooldown-seconds: 10        # short window for idempotency tests
  webhook:
    max-attempts: 3
    initial-backoff-ms: 200
    max-backoff-ms: 500
```

## Requirements

- Docker must be available at test time (Testcontainers pulls and starts `redis:7-alpine`)
- On macOS with Colima, add the `colima` Maven profile in your pom — see [`boomerang-tests/pom.xml`](pom.xml) for the pattern

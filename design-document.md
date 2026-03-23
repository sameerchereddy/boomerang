# Boomerang — Design Document

> *Boomerang turns any long-running Spring Boot endpoint into a non-blocking async API with webhook callbacks. You throw the request — it comes back when it's done.*

**Project:** `boomerang-spring-boot-starter`
**Status:** v4 — Implementation-Ready
**Stack:** Spring Boot · Redis
**Last updated:** 2026-03-22

---

## Overview

The `/sync` endpoint currently performs a full data sync — calling multiple external APIs in parallel, diffing payloads against the local store, and writing the delta — in a single blocking HTTP request. Even with parallelisation this takes ~15 seconds, and the latency will grow as the number of data sources increases.

Boomerang decouples the sync work from the HTTP response, making the API responsive regardless of how much data it processes, while remaining easy to integrate from any consumer technology stack.

**Key changes from v2:**
- **Authentication:** JWT Bearer token required on all endpoints; `sub` claim used as caller identity
- **Idempotency lock:** Global lock → per-caller scoped lock (keyed by JWT `sub` + optional idempotency key)
- **Thread pool rejection:** `CallerRunsPolicy` → `AbortPolicy` with proper `503` response
- **Job dispatch:** `@Async` fire-and-forget → Redis List polling loop for durable dispatch
- **Status endpoint:** Ownership check added; sensitive fields stripped from poll response

**Key changes from v1:**
- **Job storage:** MongoDB → Redis (better fit for job queues; TTL, atomic ops, and locking built-in)
- **Idempotency:** Distributed lock with configurable cooldown to prevent duplicate jobs
- **Security:** SSRF protection via callback URL allowlisting
- **Observability:** Micrometer metrics on all job state transitions
- **Migration:** Backward-compatible sync fallback for gradual rollout

---

## Problem Statement

| Symptom | Root cause | Impact |
|---|---|---|
| ~15s response time | All sync work blocks the HTTP thread | Poor UX, timeout risk |
| Latency grows over time | Each new data source adds to blocking work | Scalability ceiling |
| Thread pool exhaustion | Long-held threads under concurrent load | Service degradation |
| Fragile consumers | Any network hiccup during the 15s window causes a timeout | Reliability issues |

---

## Solution: Webhook (Callback) Pattern

Boomerang responds immediately with a `202 Accepted` and a `jobId`. The sync work runs in the background via a Redis-backed job queue. When complete, Boomerang POSTs the result to a `callbackUrl` provided by the consumer in the original request.

### Why this approach

- Works for **any consumer technology** — all that's required is the ability to make and receive an HTTP POST
- No long-polling, SSE, or WebSocket complexity on the consumer side
- The `jobId` + optional poll endpoint gives consumers a fallback if their webhook receiver is temporarily unavailable
- Industry-standard pattern (Stripe, GitHub, Twilio, etc.)
- Redis handles job state, TTL, and distributed locking atomically — no separate locking infrastructure needed

---

## Flow

```
Consumer                    Boomerang (Spring Boot)        Redis + Worker Pool
   |                              |                               |
   |-- POST /sync                 |                               |
   |   Authorization: Bearer JWT  |                               |
   |   { callbackUrl }  --------> |                               |
   |                              |-- validate JWT                |
   |                              |-- extract caller identity     |
   |                              |-- validate callbackUrl        |
   |                              |-- acquire scoped lock         |
   |                              |-- enqueue job to Redis -----> |
   |<-- 202 { jobId } ----------- |                           worker BRPOPs job
   |                              |                           executes sync logic
   |                              |                           updates job status
   |                              |<-- job complete --------------|
   |<-- POST callbackUrl -------- |                               |
   |   { jobId, status, result }  |    (retried on failure)       |
```

Consumers can also poll `GET /sync/{jobId}` at any point to check status. The endpoint requires the same JWT and enforces ownership — callers can only poll their own jobs.

---

## Maven Artifact

```xml
<dependency>
    <groupId>io.github.{yourhandle}</groupId>
    <artifactId>boomerang-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Module structure

| Module | Purpose |
|---|---|
| `boomerang-core` | Pure Java — models, interfaces, dispatcher. No Spring dependency |
| `boomerang-starter` | Spring Boot auto-configuration and `@EnableBoomerang` |
| `boomerang-redis` | Pluggable Redis `JobStore` implementation |
| `boomerang-sample` | Runnable demo app with Docker Compose |
| `boomerang-tests` | Shared test infrastructure — WireMock, Testcontainers |

---

## Consumer Integration

Add the dependency, annotate your app, implement your logic — that's the entire integration surface.

```java
// 1. Annotate your Spring Boot app
@SpringBootApplication
@EnableBoomerang
public class MyApp { ... }

// 2. Implement your long-running logic in a Spring bean
@Component
public class MySyncHandler {

    @BoomerangHandler
    public Object doSync(SyncContext ctx) {
        // ctx carries the jobId and any request metadata
        // your existing long-running work here — takes as long as it needs
        return Map.of("synced", 42);
    }
}
```

Boomerang scans for beans with a method annotated `@BoomerangHandler` at startup and registers the first one it finds. Only one handler is supported per application — this is intentional; Boomerang is a single-purpose async wrapper, not a general dispatcher.

---

## API Contract

All endpoints require a valid JWT Bearer token:
```
Authorization: Bearer <token>
```

Requests without a valid token receive `401 Unauthorized`. Requests with a valid token attempting to access another caller's resources receive `404 Not Found` (not `403` — avoids confirming resource existence).

### Trigger a job

```http
POST /sync
Authorization: Bearer <token>
Content-Type: application/json

{
  "callbackUrl":    "https://your-service.example.com/hooks/sync-done",
  "callbackSecret": "optional-hmac-secret",
  "idempotencyKey": "optional-caller-scoped-key"
}
```

`idempotencyKey` is optional. If omitted, it defaults to the JWT `sub` claim, meaning one concurrent job per caller. Provide an explicit key to allow finer-grained control (e.g. `"tenant-123:report-sync"`).

**Response — 202 Accepted** (returns in <50ms)
```json
{ "jobId": "550e8400-e29b-41d4-a716-446655440000" }
```

**Response — 401 Unauthorized** (missing or invalid JWT)
```json
{ "error": "Authentication required" }
```

**Response — 409 Conflict** (idempotency lock active for this caller)
```json
{
  "error": "Job already in progress or recently completed",
  "retryAfterSeconds": 300
}
```

**Response — 403 Forbidden** (callbackUrl not in allowlist)
```json
{ "error": "Callback URL not in allowlist" }
```

**Response — 503 Service Unavailable** (worker thread pool saturated)
```json
{ "error": "Server busy — retry shortly" }
```

### Poll for status (optional fallback)

```http
GET /sync/{jobId}
Authorization: Bearer <token>
```

Returns only status fields — no `callbackUrl`, `result` payload, or error detail. Ownership is enforced: a valid token for a different caller returns `404`.

**Response — 200 OK**
```json
{
  "jobId":       "550e8400-e29b-41d4-a716-446655440000",
  "status":      "IN_PROGRESS",
  "createdAt":   "2026-03-22T10:00:00Z",
  "completedAt": null
}
```

`status` values: `PENDING` · `IN_PROGRESS` · `DONE` · `FAILED`

### Webhook callback payload

**On success:**
```json
{
  "jobId":       "550e8400-e29b-41d4-a716-446655440000",
  "status":      "DONE",
  "completedAt": "2026-03-22T10:00:18Z",
  "result":      { ... }
}
```

**On failure:**
```json
{
  "jobId":       "550e8400-e29b-41d4-a716-446655440000",
  "status":      "FAILED",
  "completedAt": "2026-03-22T10:00:22Z",
  "error":       "Upstream API timeout on source: inventory"
}
```

---

## Implementation — Spring Boot

### Authentication — JWT

Boomerang uses Spring Security with a JWT filter. The `sub` claim is extracted and stored as the caller identity, used for job ownership and idempotency scoping.

```java
@Configuration
@EnableWebSecurity
public class BoomerangSecurityConfig {

    @Value("${boomerang.auth.jwt-secret}")
    private String jwtSecret;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .addFilterBefore(new BoomerangJwtFilter(jwtSecret), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/sync/**").authenticated()
                .anyRequest().permitAll()
            );
        return http.build();
    }
}

@Component
public class BoomerangJwtFilter extends OncePerRequestFilter {

    private final String jwtSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.getWriter().write("{\"error\":\"Authentication required\"}");
            return;
        }

        try {
            String token = header.substring(7);
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(UTF_8)))
                .build()
                .parseClaimsJws(token)
                .getBody();

            String callerId = claims.getSubject();
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(callerId, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(req, res);

        } catch (JwtException e) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.getWriter().write("{\"error\":\"Invalid or expired token\"}");
        }
    }
}
```

Configuration:
```yaml
boomerang:
  auth:
    jwt-secret: ${BOOMERANG_JWT_SECRET}   # min 32 chars; inject via env, never hardcode
```

### `@BoomerangHandler` — annotation, registry, and auto-configuration

**The annotation** marks the method Boomerang should invoke for every job:

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BoomerangHandler { }
```

**`SyncContext`** carries per-job metadata into the handler:

```java
@Data
@AllArgsConstructor
public class SyncContext {
    private final String jobId;
    private final String callerId;   // JWT sub — handler can use for tenant scoping
    private final Instant triggeredAt;
}
```

**`BoomerangHandlerRegistry`** scans the application context at startup, finds the annotated method, and holds a reference to invoke it reflectively:

```java
@Component
public class BoomerangHandlerRegistry implements ApplicationContextAware {

    private Object handlerBean;
    private Method handlerMethod;

    @Override
    public void setApplicationContext(ApplicationContext ctx) {
        for (String beanName : ctx.getBeanDefinitionNames()) {
            Object bean = ctx.getBean(beanName);
            for (Method method : bean.getClass().getMethods()) {
                if (method.isAnnotationPresent(BoomerangHandler.class)) {
                    if (handlerMethod != null) {
                        throw new IllegalStateException(
                            "Only one @BoomerangHandler method is allowed per application. " +
                            "Found duplicate on: " + bean.getClass().getName() + "#" + method.getName());
                    }
                    handlerBean   = bean;
                    handlerMethod = method;
                }
            }
        }
        if (handlerMethod == null) {
            throw new IllegalStateException(
                "@EnableBoomerang requires exactly one @BoomerangHandler method in the application context.");
        }
    }

    public Object invoke(SyncContext ctx) throws Exception {
        return handlerMethod.invoke(handlerBean, ctx);
    }
}
```

**`@EnableBoomerang`** triggers Boomerang's auto-configuration via `@Import`:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(BoomerangAutoConfiguration.class)
public @interface EnableBoomerang { }

@Configuration
@ConditionalOnClass(RedisTemplate.class)
public class BoomerangAutoConfiguration {
    // Registers all Boomerang beans: registry, worker, job service,
    // webhook service, security config, metrics, async config.
    // Consumers get everything by adding @EnableBoomerang — zero manual wiring.
}
```

The worker calls `handlerRegistry.invoke(ctx)` instead of the placeholder `performSync()`:

```java
SyncContext ctx = new SyncContext(jobId, (String) data.get("ownerId"), Instant.now());
Object result   = handlerRegistry.invoke(ctx);
```

### Request model

```java
@Data
@Validated
public class BoomerangRequest {
    @ValidCallbackUrl  // HTTPS + allowlist check — defined below
    private String callbackUrl;

    @Nullable
    @Size(min = 32, max = 128)
    private String callbackSecret; // optional; strongly recommended for production

    @Nullable
    @Size(max = 128)
    private String idempotencyKey; // optional; defaults to JWT sub if omitted
}
```

### `@ValidCallbackUrl` — custom constraint wired to the allowlist validator

The annotation and its `ConstraintValidator` implementation connect Bean Validation to `BoomerangCallbackUrlValidator`:

```java
@Target({ ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CallbackUrlConstraintValidator.class)
public @interface ValidCallbackUrl {
    String message() default "callbackUrl must use HTTPS and be in the allowed-domains list";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}

@Component
public class CallbackUrlConstraintValidator
        implements ConstraintValidator<ValidCallbackUrl, String> {

    // Injected by Spring — works because validators are Spring-managed beans
    @Autowired
    private BoomerangCallbackUrlValidator urlValidator;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext ctx) {
        if (value == null) return true; // null is valid — callbackUrl is optional
        return urlValidator.isAllowed(value);
    }
}
```

`callbackUrl` being `null` is valid at the constraint level — the migration fallback path allows it. The SSRF allowlist check in the controller is only reached when the value is present.

### Controller — returns 202 immediately

```java
@PostMapping("/sync")
public ResponseEntity<?> triggerSync(
        @Valid @RequestBody BoomerangRequest req,
        @AuthenticationPrincipal String callerId) {

    // 1. Validate callbackUrl against allowlist
    if (req.getCallbackUrl() != null && !callbackUrlValidator.isAllowed(req.getCallbackUrl())) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(Map.of("error", "Callback URL not in allowlist"));
    }

    // 2. Acquire per-caller idempotency lock
    String idempotencyKey = req.getIdempotencyKey() != null
        ? req.getIdempotencyKey()
        : callerId; // default: one concurrent job per caller
    String lockKey = "boomerang-lock:" + idempotencyKey;
    String jobId   = UUID.randomUUID().toString();

    Boolean lockAcquired = redisTemplate.opsForValue()
        .setIfAbsent(lockKey, jobId, cooldownSeconds, TimeUnit.SECONDS);

    if (Boolean.FALSE.equals(lockAcquired)) {
        Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of(
                "error", "Job already in progress or recently completed",
                "retryAfterSeconds", ttl != null ? ttl : cooldownSeconds
            ));
    }

    // 3. Enqueue to Redis and return immediately
    try {
        boomerangJobService.enqueueJob(jobId, callerId, lockKey, req);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId));
    } catch (TaskRejectedException e) {
        // Worker pool saturated — release lock and signal backpressure
        redisTemplate.delete(lockKey);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "30")
            .body(Map.of("error", "Server busy — retry shortly"));
    }
}

@GetMapping("/sync/{jobId}")
public ResponseEntity<BoomerangJobStatus> getStatus(
        @PathVariable String jobId,
        @AuthenticationPrincipal String callerId) {

    return boomerangJobService.getJobStatus(jobId)
        .filter(job -> job.getOwnerId().equals(callerId)) // ownership check
        .map(job -> ResponseEntity.ok(job.toStatusView()))  // strip sensitive fields
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // 404 (not 403) — avoids confirming whether the jobId exists
}
```

### Shared utility — `toJson()`

Used by `BoomerangJobService` and `BoomerangWebhookService` to serialize result objects into the Redis hash and HMAC payload. Backed by a shared Jackson `ObjectMapper` bean registered by auto-configuration:

```java
// Registered by BoomerangAutoConfiguration
@Bean
@ConditionalOnMissingBean(name = "boomerangObjectMapper")
public ObjectMapper boomerangObjectMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
}

// Used everywhere in Boomerang internals
private String toJson(Object value) {
    try {
        return boomerangObjectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
        throw new RuntimeException("Failed to serialize value to JSON", e);
    }
}
```

### Job service — Redis operations

```java
@Service
public class BoomerangJobService {

    private static final String PENDING_QUEUE = "boomerang-jobs:pending";
    private static final String JOB_PREFIX    = "boomerang-job:";

    public void enqueueJob(String jobId, String callerId, String lockKey, BoomerangRequest req) {
        Map<String, String> jobData = new HashMap<>();
        jobData.put("status",      "PENDING");
        jobData.put("ownerId",     callerId);
        jobData.put("lockKey",     lockKey);
        jobData.put("callbackUrl", req.getCallbackUrl() != null ? req.getCallbackUrl() : "");
        jobData.put("callbackSecret", req.getCallbackSecret() != null ? req.getCallbackSecret() : "");
        jobData.put("createdAt",   Instant.now().toString());
        jobData.put("completedAt", "");
        jobData.put("result",      "");
        jobData.put("error",       "");

        redisTemplate.opsForHash().putAll(JOB_PREFIX + jobId, jobData);
        redisTemplate.expire(JOB_PREFIX + jobId, 7, TimeUnit.DAYS);

        // Push to queue — the worker polls this; dispatch is Redis-driven, not in-process
        redisTemplate.opsForList().leftPush(PENDING_QUEUE, jobId);
    }

    public void updateStatus(String jobId, String status, Object result, String error) {
        String key = JOB_PREFIX + jobId;
        redisTemplate.opsForHash().put(key, "status",      status);
        redisTemplate.opsForHash().put(key, "completedAt", Instant.now().toString());
        if (result != null) redisTemplate.opsForHash().put(key, "result", toJson(result));
        if (error  != null) redisTemplate.opsForHash().put(key, "error",  error);
    }

    public Optional<BoomerangJobRecord> getJobStatus(String jobId) {
        Map<Object, Object> data = redisTemplate.opsForHash().entries(JOB_PREFIX + jobId);
        if (data.isEmpty()) return Optional.empty();
        return Optional.of(BoomerangJobRecord.from(jobId, data));
    }
}
```

### Job status model — separates full record from safe poll view

```java
@Data
public class BoomerangJobRecord {
    private String jobId;
    private String ownerId;       // internal — never exposed via API
    private String lockKey;       // internal — used to release lock on completion
    private String callbackUrl;   // internal — never exposed via API
    private String callbackSecret;// internal — never exposed via API
    private String status;
    private String createdAt;
    private String completedAt;
    private String result;        // internal — delivered via webhook only
    private String error;         // internal — delivered via webhook only

    // Safe projection returned by GET /sync/{jobId}
    public BoomerangJobStatus toStatusView() {
        return new BoomerangJobStatus(jobId, status, createdAt, completedAt);
    }

    public static BoomerangJobRecord from(String jobId, Map<Object, Object> data) {
        BoomerangJobRecord r = new BoomerangJobRecord();
        r.jobId          = jobId;
        r.ownerId        = (String) data.getOrDefault("ownerId",        "");
        r.lockKey        = (String) data.getOrDefault("lockKey",        "");
        r.callbackUrl    = (String) data.getOrDefault("callbackUrl",    "");
        r.callbackSecret = (String) data.getOrDefault("callbackSecret", "");
        r.status         = (String) data.getOrDefault("status",        "UNKNOWN");
        r.createdAt      = (String) data.getOrDefault("createdAt",      "");
        r.completedAt    = (String) data.getOrDefault("completedAt",    null);
        r.result         = (String) data.getOrDefault("result",         null);
        r.error          = (String) data.getOrDefault("error",          null);
        return r;
    }
}

@Data
@AllArgsConstructor
public class BoomerangJobStatus {
    private String jobId;
    private String status;
    private String createdAt;
    private String completedAt;
}
```

### Async worker — Redis List polling (durable dispatch)

The worker polls Redis directly via `BRPOP`. Jobs survive worker restarts because they remain in the list until explicitly popped — no in-process state is lost on JVM crash.

```java
@Service
public class BoomerangWorker {

    private static final String PENDING_QUEUE = "boomerang-jobs:pending";
    private volatile boolean running = true;

    @PostConstruct
    public void startPolling() {
        // Dedicated polling thread — separate from the HTTP thread pool
        Executors.newSingleThreadExecutor(r -> new Thread(r, "boomerang-poller"))
            .submit(this::pollLoop);
    }

    private void pollLoop() {
        while (running) {
            try {
                // BRPOP blocks up to 5s — no busy-waiting
                String jobId = redisTemplate.opsForList()
                    .rightPop(PENDING_QUEUE, Duration.ofSeconds(5));
                if (jobId != null) {
                    try {
                        taskExecutor.execute(() -> processJob(jobId));
                    } catch (RejectedExecutionException e) {
                        // Pool saturated — push the jobId back to the front of the queue
                        // so it is not lost. The 503 was already returned to the HTTP caller
                        // at enqueue time; this guards the poller's own submission path.
                        log.warn("Worker pool full, re-queuing job {}", jobId);
                        redisTemplate.opsForList().rightPush(PENDING_QUEUE, jobId);
                        boomerangMetrics.poolRejections.increment();
                        Thread.sleep(1000); // brief back-off before next poll
                    }
                }
            } catch (Exception e) {
                log.error("Poller error", e);
            }
        }
    }

    private void processJob(String jobId) {
        Map<Object, Object> data = redisTemplate.opsForHash()
            .entries("boomerang-job:" + jobId);
        String lockKey     = (String) data.get("lockKey");
        String callbackUrl = (String) data.get("callbackUrl");
        String secret      = (String) data.get("callbackSecret");

        try {
            boomerangJobService.updateStatus(jobId, "IN_PROGRESS", null, null);

            SyncContext ctx = new SyncContext(jobId, (String) data.get("ownerId"), Instant.now());
            Object result   = handlerRegistry.invoke(ctx);

            boomerangJobService.updateStatus(jobId, "DONE", result, null);

            if (!callbackUrl.isEmpty()) {
                boomerangWebhookService.fire(callbackUrl, jobId, "DONE", result, secret);
            }

        } catch (Exception e) {
            boomerangJobService.updateStatus(jobId, "FAILED", null, e.getMessage());

            if (!callbackUrl.isEmpty()) {
                boomerangWebhookService.fire(callbackUrl, jobId, "FAILED", null, secret);
            }

        } finally {
            redisTemplate.delete(lockKey); // always release the scoped idempotency lock
        }
    }

    @PreDestroy
    public void stop() { running = false; }
}
```

### Failed webhook store — Redis-backed

Failed webhook deliveries are stored in Redis as individual hashes, keyed by `jobId`. No separate database is required — Redis is the only infrastructure dependency.

```java
@Component
public class BoomerangFailedWebhookStore {

    private static final String FAILED_PREFIX = "boomerang-failed-webhook:";

    @Value("${boomerang.failed-webhook-ttl-days:30}")
    private int ttlDays;

    public void save(String jobId, String url, Object payload, String lastError) {
        String key = FAILED_PREFIX + jobId;
        Map<String, String> entry = new HashMap<>();
        entry.put("jobId",       jobId);
        entry.put("callbackUrl", url);
        entry.put("payload",     toJson(payload));
        entry.put("failedAt",    Instant.now().toString());
        entry.put("lastError",   lastError);
        entry.put("attempts",    "5");

        redisTemplate.opsForHash().putAll(key, entry);
        redisTemplate.expire(key, ttlDays, TimeUnit.DAYS);

        log.error("Webhook delivery permanently failed for job {} → stored for manual replay", jobId);
    }

    // Returns all failed entries — used by the scheduled replay job
    // Uses SCAN instead of KEYS to avoid blocking Redis on large keyspaces
    public List<Map<Object, Object>> findAll() {
        List<Map<Object, Object>> results = new ArrayList<>();
        ScanOptions opts = ScanOptions.scanOptions()
            .match(FAILED_PREFIX + "*")
            .count(100)
            .build();
        try (Cursor<String> cursor = redisTemplate.scan(opts)) {
            cursor.forEachRemaining(k ->
                results.add(redisTemplate.opsForHash().entries(k)));
        }
        return results;
    }

    public void delete(String jobId) {
        redisTemplate.delete(FAILED_PREFIX + jobId);
    }
}
```

The Redis data retention table already accounts for a 30-day TTL on these entries. A `@Scheduled` replay job (not shown) calls `findAll()`, retries delivery, and calls `delete()` on success.

### Thread pool configuration — AbortPolicy

`AbortPolicy` ensures the HTTP thread is never used to run a job. Rejection is surfaced as a `503` in the controller (see above).

```java
@Configuration
@EnableAsync
public class BoomerangAsyncConfig {

    @Bean("boomerangTaskExecutor")
    public Executor boomerangTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("boomerang-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy()); // never run on HTTP thread
        executor.initialize();
        return executor;
    }
}
```

### Webhook service — dispatch with retry and HMAC signing

`RestClient` (Spring Boot 3.2+) replaces the deprecated `RestTemplate` for outbound calls. A dedicated bean is registered by `BoomerangAutoConfiguration` so consumers don't need to configure it:

```java
// In BoomerangAutoConfiguration
@Bean("boomerangRestClient")
@ConditionalOnMissingBean(name = "boomerangRestClient")
public RestClient boomerangRestClient() {
    return RestClient.builder()
        .requestFactory(new HttpComponentsClientHttpRequestFactory()) // connection pooling
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build();
}
```

```java
@Service
public class BoomerangWebhookService {

    private final RestClient restClient;
    private final BoomerangFailedWebhookStore failedWebhookStore;

    public BoomerangWebhookService(
            @Qualifier("boomerangRestClient") RestClient restClient,
            BoomerangFailedWebhookStore failedWebhookStore) {
        this.restClient         = restClient;
        this.failedWebhookStore = failedWebhookStore;
    }

    public void fire(String url, String jobId, String status, Object result, String secret) {
        BoomerangPayload payload = new BoomerangPayload(jobId, status, result, Instant.now());
        String body = toJson(payload);

        RetryTemplate retry = RetryTemplate.builder()
            .maxAttempts(5)
            .exponentialBackoff(1000, 2, 30000) // 1s → 2s → 4s → 8s → 16s
            .build();

        try {
            retry.execute(ctx -> {
                var spec = restClient.post()
                    .uri(url)
                    .body(body);
                if (secret != null && !secret.isEmpty()) {
                    spec = spec.header("X-Signature-SHA256", "sha256=" + hmacSha256(body, secret));
                }
                spec.retrieve().toBodilessEntity();
                return null;
            });
        } catch (Exception e) {
            failedWebhookStore.save(jobId, url, payload, e.getMessage());
        }
    }

    private String hmacSha256(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
            return Hex.encodeHexString(mac.doFinal(body.getBytes(UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate HMAC signature", e);
        }
    }
}
```

Note: HMAC is now computed over the serialised `body` string rather than the payload object — this guarantees the signature matches exactly what is sent over the wire.

---

## Data Storage — Redis

### Job metadata (Hash)

```
KEY    boomerang-job:{jobId}
TTL    7 days

Fields:
  status         PENDING | IN_PROGRESS | DONE | FAILED
  ownerId        <JWT sub claim — used for ownership checks>
  lockKey        boomerang-lock:<idempotencyKey> — released by worker on completion
  callbackUrl    https://...
  callbackSecret <hmac secret>
  createdAt      2026-03-22T10:00:00Z
  completedAt    2026-03-22T10:00:18Z
  result         { ...json... }
  error          <message if FAILED>
```

### Pending queue (List)

```
KEY    boomerang-jobs:pending
       LPUSH on enqueue · BRPOP by worker poller · job persists until popped
```

The worker uses `BRPOP` (blocking pop with timeout). Jobs remain in Redis until the worker pops them — a JVM crash does not lose enqueued work.

### Idempotency lock (String + TTL) — per caller

```
KEY    boomerang-lock:{idempotencyKey}
CMD    SET boomerang-lock:{idempotencyKey} {jobId} EX 300 NX
       → OK    lock acquired, proceed
       → nil   lock exists, return 409
```

Different callers (different `idempotencyKey` values) can hold locks simultaneously. The lock is released by the worker's `finally` block. If the worker crashes, Redis TTL expires it automatically.

### Data retention

| Data | Storage | TTL | Purpose |
|---|---|---|---|
| Job metadata | Redis Hash | 7 days | Status polling, audit trail |
| Idempotency lock | Redis String | 5 min (configurable) | Prevent duplicate jobs per caller |
| Failed webhooks | Redis Hash (`boomerang-failed-webhook:{jobId}`) | 30 days | Manual replay, investigation |

---

## Security

### Authentication — JWT

All `/sync/**` endpoints require a JWT Bearer token. The token must be signed with the configured secret (HS256). The `sub` claim is used as the caller identity throughout the system.

| Scenario | Response |
|---|---|
| No `Authorization` header | 401 Unauthorized |
| Malformed or expired token | 401 Unauthorized |
| Valid token, wrong owner's job | 404 Not Found |
| Valid token, own job | 200 OK |

**JWT claims used by Boomerang:**

| Claim | Usage |
|---|---|
| `sub` | Caller identity — scopes idempotency lock and job ownership |
| `exp` | Token expiry — enforced by the JWT filter |

Boomerang does not issue tokens. Consumers obtain JWTs from their own identity provider and pass them in the `Authorization` header.

### SSRF prevention — callback URL allowlisting

All `callbackUrl` values are validated before a job is accepted:

```java
@Component
public class BoomerangCallbackUrlValidator {

    @Value("${boomerang.callback.allowed-domains}")
    private List<String> allowedDomains;

    public boolean isAllowed(String callbackUrl) {
        try {
            URL url = new URL(callbackUrl);
            String host = url.getHost().toLowerCase();

            if (!"https".equals(url.getProtocol())) return false;
            if (host.matches("^[0-9.:]+$")) return false;
            if (host.equals("localhost") || host.startsWith("127.") || host.equals("::1")) return false;

            return allowedDomains.stream()
                .anyMatch(d -> host.equals(d) || host.endsWith("." + d));

        } catch (MalformedURLException e) {
            return false;
        }
    }
}
```

| Protection layer | What it blocks |
|---|---|
| HTTPS required | Plaintext interception |
| No IP addresses | Direct internal network access |
| No localhost | Loopback attacks |
| Domain allowlist | Arbitrary external or internal endpoints |

### HMAC signature verification

If a `callbackSecret` is provided, every webhook POST includes:

```
X-Signature-SHA256: sha256=<hmac-hex>
```

**Consumer-side verification:**
```java
String received = request.getHeader("X-Signature-SHA256");
String computed = "sha256=" + hmacSha256(requestBody, storedSecret);

// Constant-time comparison — prevents timing attacks
if (!MessageDigest.isEqual(computed.getBytes(UTF_8), received.getBytes(UTF_8))) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
}
```

### Consumer security checklist

- Attach a valid JWT `Authorization: Bearer <token>` on every request
- Validate `X-Signature-SHA256` before processing any webhook
- Return `200` promptly — do heavy work asynchronously on your side
- Use `jobId` for idempotency — retries may deliver the same webhook more than once

---

## Observability

### Micrometer metrics

```java
@Component
public class BoomerangMetrics {
    Counter jobsCreated       = Counter.builder("boomerang.jobs.created").register(registry);
    Counter jobsCompleted     = Counter.builder("boomerang.jobs.completed").register(registry);
    Counter jobsFailed        = Counter.builder("boomerang.jobs.failed").register(registry);
    Counter idempotencyBlocks = Counter.builder("boomerang.idempotency.blocks").register(registry);
    Counter webhookSuccesses  = Counter.builder("boomerang.webhook.success").register(registry);
    Counter webhookFailures   = Counter.builder("boomerang.webhook.failure").register(registry);
    Counter poolRejections    = Counter.builder("boomerang.pool.rejections").register(registry);
    Gauge   queueDepth        = Gauge.builder("boomerang.queue.depth", this::getQueueDepth).register(registry);
    Timer   jobDuration       = Timer.builder("boomerang.job.duration").register(registry);
    Timer   webhookDuration   = Timer.builder("boomerang.webhook.duration").register(registry);
}
```

### Recommended alerts

| Metric | Warning | Critical | Action |
|---|---|---|---|
| `boomerang.queue.depth` | > 50 | > 200 | Scale worker pool |
| `boomerang.pool.rejections` | any | > 10/min | Increase max-size or queue-capacity |
| `boomerang.webhook.failure` rate | > 5% | > 20% | Investigate consumer endpoints |
| `boomerang.job.duration` P95 | > 30s | > 60s | Check upstream data sources |
| `boomerang.jobs.failed` rate | > 2% | > 10% | Check downstream connectivity |

---

## Configuration

Boomerang uses standard Spring Boot Redis auto-configuration — no Boomerang-specific Redis setup is needed. Consumers configure their Redis connection the usual way:

```yaml
# Redis — standard Spring Boot, Boomerang picks this up automatically
spring:
  data:
    redis:
      host: ${REDIS_HOST}         # e.g. your-redis.cache.amazonaws.com
      port: 6379
      password: ${REDIS_PASSWORD} # omit if no auth required
      ssl:
        enabled: true             # recommended for production; omit for local dev
```

For local development, a one-liner is enough: `docker run -d -p 6379:6379 redis:7-alpine` with `host: localhost`.

```yaml
boomerang:
  auth:
    jwt-secret: ${BOOMERANG_JWT_SECRET}   # min 32 chars; inject via env

  callback:
    allowed-domains:
      - your-org.com
      - your-internal-domain.net
    # Optional: more restrictive specific-URL allowlist
    allowed-urls:
      - https://service-a.your-org.com/hooks/
      - https://service-b.your-org.com/callbacks/

  idempotency:
    cooldown-seconds: 300     # 5 min default — tune per environment

  thread-pool:
    core-size: 5
    max-size: 20
    queue-capacity: 100

  job-ttl-days: 7
  failed-webhook-ttl-days: 30
```

---

## Migration Strategy

Boomerang supports a **backward-compatible fallback** — if `callbackUrl` is omitted, the endpoint behaves synchronously. This allows gradual consumer migration without a flag day. Authentication is required in both paths.

```java
@PostMapping("/sync")
public ResponseEntity<?> triggerSync(
        @Valid @RequestBody BoomerangRequest req,
        @AuthenticationPrincipal String callerId) {

    // Legacy path: no callbackUrl → synchronous response (deprecated)
    if (req.getCallbackUrl() == null) {
        Object result = syncService.performSync(req);
        return ResponseEntity.ok(result);
    }

    // Boomerang path: callbackUrl provided → 202 + webhook
    // ... (async implementation above)
}
```

### Rollout phases

**Phase 1 — Parallel deployment (weeks 1–2)**
Deploy with sync fallback active. New consumers adopt Boomerang. Existing consumers are unaffected.

**Phase 2 — Consumer migration (month 2)**
Update internal consumers to provide `callbackUrl` and JWT. Monitor webhook adoption via `boomerang.jobs.created` metrics.

**Phase 3 — Deprecation notice (month 3)**
Mark sync fallback as deprecated in API docs. Add a `Deprecation` response header to sync responses. Communicate sunset date.

**Phase 4 — Removal (month 9)**
Remove sync fallback. Boomerang-only. Update consumer guide accordingly.

---

## Operational Concerns

### Retry policy

| Attempt | Delay |
|---|---|
| 1 | immediate |
| 2 | 1s |
| 3 | 2s |
| 4 | 4s |
| 5 | 8s |

After 5 consecutive failures, the delivery is written to `boomerang_failed_webhooks` for manual inspection or scheduled replay.

### Dead letter handling

```json
{
  "jobId":       "abc-123",
  "callbackUrl": "https://...",
  "failedAt":    "2026-03-22T10:05:00Z",
  "lastError":   "Connection refused",
  "attempts":    5
}
```

A scheduled job replays these once the consumer endpoint recovers.

### Idempotency lock behaviour

| Scenario | Outcome |
|---|---|
| Job enqueued, worker running | Scoped lock held → 409 for same caller only |
| Different caller, same time | Different lock key → proceeds normally |
| Job completes (success or failure) | Worker releases lock in `finally` block |
| Worker crashes mid-job | Redis TTL expires lock automatically after cooldown |
| Consumer retries after cooldown | Lock gone → new job accepted normally |

---

## Future Considerations

### Redis Streams (if scale demands it)

The current implementation uses Redis Lists + Hashes — simple, sufficient, and well-understood. If production reveals limitations, migrate to Redis Streams:

```java
// Current: List-based queue
redisTemplate.opsForList().leftPush("boomerang-jobs:pending", jobId);

// Future: Stream with consumer group acknowledgement
redisTemplate.opsForStream().add(
    StreamRecords.newRecord().in("boomerang-jobs-stream").ofObject(jobData));
```

The consumer-facing webhook contract remains identical — this is a purely internal change.

**Migrate only if:** queue depth issues appear in production, jobs are lost on worker restarts, or consumer group semantics are needed for multiple competing workers.

### Other extensions

- **SSE / WebSocket** — for browser-based consumers that cannot host a webhook receiver, `GET /sync/{jobId}/stream` via Server-Sent Events is a natural complement
- **Priority queues** — route lightweight vs. heavyweight jobs to separate `boomerangTaskExecutor` pools
- **Batch jobs** — trigger work across multiple resources in one job; recommended only if consumer demand is clear
- **Job cancellation** — `DELETE /sync/{jobId}` to abort in-flight jobs; requires a cancellation flag in Redis polled by the worker
- **Token issuance** — if Boomerang consumers are all internal services, a lightweight `/auth/token` endpoint backed by client credentials can be added to the starter to avoid requiring a separate IdP

> For the multi-language roadmap (standalone service + thin SDKs for Node, Python, Go), see `multi-language-roadmap.md`.

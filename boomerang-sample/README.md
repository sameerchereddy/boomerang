# boomerang-sample

A minimal but complete Spring Boot application showing Boomerang in action. Use this as a reference implementation or a local sandbox.

## What's in it

- `SampleApp` — `@SpringBootApplication @EnableBoomerang` entry point
- `EchoHandler` — a single `@BoomerangHandler` that sleeps 500 ms (simulating work) and echoes job metadata back in the result
- `application.yml` — wired to a local Redis and a dev JWT secret
- `docker-compose.yml` — starts both Redis and the sample app

## Running it

```bash
# Build
mvn package -pl boomerang-sample -am -DskipTests

# Start Redis + sample app
cd boomerang-sample
docker compose up
```

The app listens on port `8080`. Generate a JWT with any HS256 tool using the secret from `docker-compose.yml` (`boomerang-dev-secret-key-min-32-chars!!`):

```bash
# Using jwt-cli (https://github.com/mike-engel/jwt-cli)
JWT=$(jwt encode --secret "boomerang-dev-secret-key-min-32-chars!!" --sub demo)

# Trigger a job (sample app uses base-path: /jobs)
curl -X POST http://localhost:8080/jobs \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"callbackUrl":"https://webhook.site/your-unique-url"}'

# → 202 { "jobId": "..." }

# Poll status
curl http://localhost:8080/jobs/<jobId> \
  -H "Authorization: Bearer $JWT"
```

Use [webhook.site](https://webhook.site) to see the callback delivery in real time.

## Integration tests

The sample also has a `SyncIT` test suite (7 tests) that runs against a local Redis and verifies the full job lifecycle — auth, trigger, callback delivery, retries, status polling, idempotency. Run with:

```bash
mvn verify -pl boomerang-sample -am
```

Requires Redis running on `localhost:6379`.

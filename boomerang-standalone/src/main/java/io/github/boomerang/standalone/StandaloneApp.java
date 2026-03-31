package io.github.boomerang.standalone;

import io.github.boomerang.starter.annotation.EnableBoomerang;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runnable Boomerang service for standalone mode.
 *
 * <p>This application intentionally contains no {@code @BoomerangHandler} method.
 * All jobs are expected to carry a {@code workerUrl} — Boomerang calls the consumer's
 * existing endpoint over HTTP to perform the work, then fires the webhook callback.
 *
 * <p>Minimum required environment variables:
 * <pre>
 *   BOOMERANG_JWT_SECRET              HS256 signing secret (min 32 chars)
 *   SPRING_DATA_REDIS_HOST            Redis hostname (default: localhost)
 *   BOOMERANG_CALLBACK_ALLOWED_DOMAINS  Comma-separated allowed callback/worker domains
 * </pre>
 *
 * <p>Quick start with Docker Compose:
 * <pre>
 *   docker compose -f boomerang-standalone/docker-compose.yml up
 * </pre>
 */
@SpringBootApplication
@EnableBoomerang
public class StandaloneApp {

    public static void main(String[] args) {
        SpringApplication.run(StandaloneApp.class, args);
    }
}

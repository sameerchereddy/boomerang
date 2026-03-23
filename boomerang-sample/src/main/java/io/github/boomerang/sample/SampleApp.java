package io.github.boomerang.sample;

import io.github.boomerang.starter.annotation.EnableBoomerang;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Sample Spring Boot application demonstrating Boomerang integration. Run with:
 * <pre>
 *   docker compose up -d          # start Redis
 *   ./mvnw spring-boot:run -pl boomerang-sample
 * </pre>
 *
 * Then trigger a job:
 * <pre>
 *   curl -X POST http://localhost:8080/sync \
 *     -H "Authorization: Bearer &lt;token&gt;" \
 *     -H "Content-Type: application/json" \
 *     -d '{"callbackUrl":"https://webhook.site/your-uuid"}'
 * </pre>
 */
@SpringBootApplication
@EnableBoomerang
public class SampleApp {

    public static void main(String[] args) {
        SpringApplication.run(SampleApp.class, args);
    }
}

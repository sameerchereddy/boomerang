package io.github.boomerang.tests.app;

import io.github.boomerang.starter.annotation.EnableBoomerang;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application for standalone mode integration tests.
 * Intentionally contains no {@code @BoomerangHandler} — all jobs must supply a
 * {@code workerUrl}.
 */
@SpringBootApplication
@EnableBoomerang
public class TestStandaloneApp {
    public static void main(String[] args) {
        SpringApplication.run(TestStandaloneApp.class, args);
    }
}

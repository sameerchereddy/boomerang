package io.github.boomerang.tests.app;

import io.github.boomerang.starter.annotation.EnableBoomerang;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application used as the test harness for
 * {@link io.github.boomerang.tests.BoomerangSyncIT}.
 */
@SpringBootApplication
@EnableBoomerang
public class TestSyncApp {
    public static void main(String[] args) {
        SpringApplication.run(TestSyncApp.class, args);
    }
}

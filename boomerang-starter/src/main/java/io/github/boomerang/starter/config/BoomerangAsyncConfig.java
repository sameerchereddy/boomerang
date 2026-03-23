package io.github.boomerang.starter.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Activates Spring's {@code @Async} infrastructure. The actual worker thread pool
 * ({@code boomerangTaskExecutor}) is registered in
 * {@link io.github.boomerang.starter.autoconfigure.BoomerangAutoConfiguration} and used
 * by {@link io.github.boomerang.starter.service.BoomerangWorker} for job execution.
 */
@Configuration
@EnableAsync
public class BoomerangAsyncConfig {
}

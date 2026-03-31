package io.github.boomerang.starter.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.boomerang.redis.BoomerangFailedWebhookStore;
import io.github.boomerang.redis.RedisBoomerangJobStore;
import io.github.boomerang.store.BoomerangJobStore;
import io.github.boomerang.starter.config.BoomerangAsyncConfig;
import io.github.boomerang.starter.config.BoomerangProperties;
import io.github.boomerang.starter.controller.BoomerangController;
import io.github.boomerang.starter.metrics.BoomerangMetrics;
import io.github.boomerang.starter.registry.BoomerangHandlerRegistry;
import io.github.boomerang.starter.security.BoomerangSecurityConfig;
import io.github.boomerang.starter.service.BoomerangWebhookService;
import io.github.boomerang.starter.service.BoomerangWorker;
import io.github.boomerang.starter.service.StandaloneWorkerInvoker;
import io.github.boomerang.starter.validation.BoomerangCallbackUrlValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Spring Boot auto-configuration for Boomerang. Activated when the consuming application
 * places {@code @EnableBoomerang} on its main class. All beans are guarded by
 * {@code @ConditionalOnMissingBean} so consumers can override any component by declaring
 * their own bean of the same type.
 *
 * <p>The configuration is also listed in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * so it participates in Spring Boot's standard auto-configuration mechanism.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(StringRedisTemplate.class)
@EnableConfigurationProperties(BoomerangProperties.class)
@Import({BoomerangSecurityConfig.class, BoomerangAsyncConfig.class})
public class BoomerangAutoConfiguration {

    // -------------------------------------------------------------------------
    // Shared infrastructure
    // -------------------------------------------------------------------------

    /**
     * Jackson ObjectMapper pre-configured for Boomerang's internal use: ISO-8601 date
     * strings, no timestamps. Consumers may provide their own bean named
     * {@code boomerangObjectMapper} to customise serialisation behaviour.
     */
    @Bean("boomerangObjectMapper")
    @ConditionalOnMissingBean(name = "boomerangObjectMapper")
    public ObjectMapper boomerangObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Dedicated {@link RestClient} for outbound webhook deliveries. Uses a pooled
     * Apache HttpClient 5 connection factory for efficient connection reuse.
     */
    @Bean("boomerangRestClient")
    @ConditionalOnMissingBean(name = "boomerangRestClient")
    public RestClient boomerangRestClient() {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault());
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * Dedicated {@link RestClient} for worker invocations in standalone mode. Uses a
     * separate Apache HttpClient configured with a long response timeout derived from
     * {@code boomerang.worker.timeout-seconds} to support long-running jobs without
     * affecting the webhook client's timeout settings.
     */
    @Bean("boomerangWorkerRestClient")
    @ConditionalOnMissingBean(name = "boomerangWorkerRestClient")
    public RestClient boomerangWorkerRestClient(BoomerangProperties props) {
        int timeoutSeconds = props.getWorker().getTimeoutSeconds();
        org.apache.hc.client5.http.config.RequestConfig requestConfig =
                org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(timeoutSeconds))
                        .build();
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(
                        HttpClientBuilder.create().setDefaultRequestConfig(requestConfig).build());
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    /**
     * Worker thread pool. Uses {@link ThreadPoolExecutor.AbortPolicy} so that rejection
     * surfaces as a {@link java.util.concurrent.RejectedExecutionException} which the
     * poller catches and handles by re-queuing the job — the HTTP thread is never used
     * to run a job.
     */
    @Bean("boomerangTaskExecutor")
    @ConditionalOnMissingBean(name = "boomerangTaskExecutor")
    public Executor boomerangTaskExecutor(BoomerangProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(props.getThreadPool().getCoreSize());
        executor.setMaxPoolSize(props.getThreadPool().getMaxSize());
        executor.setQueueCapacity(props.getThreadPool().getQueueCapacity());
        executor.setThreadNamePrefix("boomerang-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    // -------------------------------------------------------------------------
    // Store layer
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(BoomerangJobStore.class)
    public RedisBoomerangJobStore redisBoomerangJobStore(
            StringRedisTemplate redisTemplate,
            @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
            BoomerangProperties props) {
        return new RedisBoomerangJobStore(redisTemplate, objectMapper, props.getJobTtlDays());
    }

    @Bean
    @ConditionalOnMissingBean(BoomerangFailedWebhookStore.class)
    public BoomerangFailedWebhookStore boomerangFailedWebhookStore(
            StringRedisTemplate redisTemplate,
            @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
            BoomerangProperties props) {
        return new BoomerangFailedWebhookStore(redisTemplate, objectMapper,
                props.getFailedWebhookTtlDays());
    }

    // -------------------------------------------------------------------------
    // Core components
    // -------------------------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(BoomerangHandlerRegistry.class)
    public BoomerangHandlerRegistry boomerangHandlerRegistry(ApplicationContext applicationContext) {
        return new BoomerangHandlerRegistry(applicationContext);
    }

    @Bean
    @ConditionalOnMissingBean(BoomerangCallbackUrlValidator.class)
    public BoomerangCallbackUrlValidator boomerangCallbackUrlValidator(BoomerangProperties props) {
        return new BoomerangCallbackUrlValidator(props);
    }

    @Bean
    @ConditionalOnMissingBean(BoomerangMetrics.class)
    public BoomerangMetrics boomerangMetrics(MeterRegistry registry,
                                              StringRedisTemplate redisTemplate) {
        return new BoomerangMetrics(registry, redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(BoomerangWebhookService.class)
    public BoomerangWebhookService boomerangWebhookService(
            @Qualifier("boomerangRestClient") RestClient restClient,
            BoomerangFailedWebhookStore failedWebhookStore,
            @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
            BoomerangProperties props) {
        BoomerangProperties.Webhook w = props.getWebhook();
        return new BoomerangWebhookService(restClient, failedWebhookStore, objectMapper,
                w.getMaxAttempts(), w.getInitialBackoffMs(), w.getMaxBackoffMs());
    }

    @Bean
    @ConditionalOnMissingBean(StandaloneWorkerInvoker.class)
    public StandaloneWorkerInvoker standaloneWorkerInvoker(
            @Qualifier("boomerangWorkerRestClient") RestClient workerRestClient,
            @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
            BoomerangProperties props) {
        BoomerangProperties.Worker w = props.getWorker();
        return new StandaloneWorkerInvoker(workerRestClient, objectMapper,
                w.getMaxAttempts(), w.getMaxResponseSizeBytes());
    }

    @Bean
    @ConditionalOnMissingBean(BoomerangWorker.class)
    public BoomerangWorker boomerangWorker(StringRedisTemplate redisTemplate,
                                            BoomerangJobStore jobStore,
                                            BoomerangHandlerRegistry handlerRegistry,
                                            BoomerangWebhookService webhookService,
                                            StandaloneWorkerInvoker standaloneWorkerInvoker,
                                            BoomerangMetrics metrics,
                                            @Qualifier("boomerangObjectMapper") ObjectMapper objectMapper,
                                            @Qualifier("boomerangTaskExecutor") Executor taskExecutor) {
        return new BoomerangWorker(redisTemplate, jobStore, handlerRegistry,
                webhookService, standaloneWorkerInvoker, metrics, objectMapper, taskExecutor);
    }

    @Bean
    @ConditionalOnMissingBean(BoomerangController.class)
    public BoomerangController boomerangController(BoomerangJobStore jobStore,
                                                    StringRedisTemplate redisTemplate,
                                                    BoomerangCallbackUrlValidator callbackUrlValidator,
                                                    BoomerangProperties props,
                                                    BoomerangMetrics metrics,
                                                    BoomerangFailedWebhookStore failedWebhookStore,
                                                    BoomerangWebhookService webhookService) {
        return new BoomerangController(jobStore, redisTemplate, callbackUrlValidator, props,
                metrics, failedWebhookStore, webhookService);
    }
}

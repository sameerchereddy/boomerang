package io.github.boomerang.starter.annotation;

import io.github.boomerang.starter.autoconfigure.BoomerangAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables Boomerang's auto-configuration when placed on a {@code @SpringBootApplication}
 * class. Imports {@link BoomerangAutoConfiguration} which registers all required beans:
 * the job store, worker, webhook service, JWT security filter, and Micrometer metrics.
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableBoomerang
 * public class MyApp {
 *     public static void main(String[] args) {
 *         SpringApplication.run(MyApp.class, args);
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(BoomerangAutoConfiguration.class)
public @interface EnableBoomerang {
}

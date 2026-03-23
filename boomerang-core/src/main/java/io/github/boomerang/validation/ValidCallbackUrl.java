package io.github.boomerang.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Validates that a callback URL uses HTTPS and belongs to an allowed domain. The actual
 * {@link jakarta.validation.ConstraintValidator} implementation is registered as a Spring
 * bean in {@code boomerang-starter} and is picked up automatically by Spring's validation
 * infrastructure when present in the application context.
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {NoOpCallbackUrlValidator.class})
public @interface ValidCallbackUrl {

    String message() default "callbackUrl must use HTTPS and be in the allowed-domains list";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

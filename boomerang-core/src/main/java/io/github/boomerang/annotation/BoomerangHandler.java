package io.github.boomerang.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the Boomerang job handler. Exactly one method across the entire
 * application context must carry this annotation. The method must accept a single
 * {@link io.github.boomerang.model.SyncContext} argument and may return any serialisable
 * result (or {@code void}).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BoomerangHandler {
}

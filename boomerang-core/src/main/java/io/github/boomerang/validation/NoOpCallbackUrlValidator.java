package io.github.boomerang.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Default no-op {@link ConstraintValidator} for {@link ValidCallbackUrl}. Always returns
 * {@code true} — the real SSRF allowlist check is performed in the controller layer by
 * {@code BoomerangCallbackUrlValidator}, which has access to the configured allowed-domains
 * list. This validator exists solely to satisfy the Jakarta Validation contract so that
 * {@code @ValidCallbackUrl} on {@link io.github.boomerang.model.BoomerangRequest} does not
 * cause an {@code UnexpectedTypeException} at startup.
 */
public class NoOpCallbackUrlValidator implements ConstraintValidator<ValidCallbackUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return true;
    }
}

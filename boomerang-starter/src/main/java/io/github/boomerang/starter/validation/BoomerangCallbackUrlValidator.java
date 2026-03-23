package io.github.boomerang.starter.validation;

import io.github.boomerang.starter.config.BoomerangProperties;
import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

/**
 * Validates {@code callbackUrl} values against the configured allowlist and a set of
 * SSRF-prevention rules. Injected into the controller and evaluated before a job is
 * accepted.
 *
 * <p>When {@code boomerang.callback.skip-validation=true} all checks are bypassed — this
 * mode is provided for local development only and must never be enabled in production.
 */
@Slf4j
public class BoomerangCallbackUrlValidator {

    private final BoomerangProperties properties;

    public BoomerangCallbackUrlValidator(BoomerangProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns {@code true} if the given URL is safe to use as a callback destination.
     *
     * @param callbackUrl the URL to validate; {@code null} is considered valid (optional field)
     */
    public boolean isAllowed(String callbackUrl) {
        if (callbackUrl == null) {
            return true;
        }

        if (properties.getCallback().isSkipValidation()) {
            log.warn("SSRF validation is disabled (boomerang.callback.skip-validation=true). " +
                     "Do not use this setting in production.");
            return true;
        }

        try {
            URL url = new URL(callbackUrl);
            String host = url.getHost().toLowerCase();

            // Require HTTPS
            if (!"https".equals(url.getProtocol())) {
                log.debug("Rejected callbackUrl — not HTTPS: {}", callbackUrl);
                return false;
            }

            // Block raw IP addresses (IPv4 and IPv6)
            if (host.matches("^[0-9.:]+$")) {
                log.debug("Rejected callbackUrl — IP address not allowed: {}", callbackUrl);
                return false;
            }

            // Block loopback / link-local addresses
            if (host.equals("localhost")
                    || host.startsWith("127.")
                    || host.equals("::1")
                    || host.startsWith("169.254.")) {
                log.debug("Rejected callbackUrl — loopback/link-local not allowed: {}", callbackUrl);
                return false;
            }

            // Domain allowlist check
            List<String> allowedDomains = properties.getCallback().getAllowedDomains();
            if (!allowedDomains.isEmpty()) {
                boolean domainAllowed = allowedDomains.stream()
                        .anyMatch(d -> host.equals(d) || host.endsWith("." + d));
                if (!domainAllowed) {
                    log.debug("Rejected callbackUrl — host '{}' not in allowed-domains list", host);
                    return false;
                }
            }

            // Optional specific-URL prefix allowlist
            List<String> allowedUrls = properties.getCallback().getAllowedUrls();
            if (!allowedUrls.isEmpty()) {
                boolean urlAllowed = allowedUrls.stream()
                        .anyMatch(callbackUrl::startsWith);
                if (!urlAllowed) {
                    log.debug("Rejected callbackUrl — '{}' not in allowed-urls list", callbackUrl);
                    return false;
                }
            }

            return true;

        } catch (MalformedURLException e) {
            log.debug("Rejected callbackUrl — malformed URL: {}", callbackUrl);
            return false;
        }
    }
}

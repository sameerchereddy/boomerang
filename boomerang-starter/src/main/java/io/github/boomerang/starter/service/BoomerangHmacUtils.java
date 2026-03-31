package io.github.boomerang.starter.service;

import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Package-private utility for computing HmacSHA256 signatures used by both
 * {@link BoomerangWebhookService} (outgoing webhooks) and
 * {@link StandaloneWorkerInvoker} (outgoing worker invocations).
 *
 * <p>The signature format is {@code sha256=<lowercase hex>} as specified by the
 * Boomerang cross-language contract.
 */
final class BoomerangHmacUtils {

    private BoomerangHmacUtils() {}

    /**
     * Computes {@code sha256=<lowercase hex>} over the given body string using the
     * provided secret.
     *
     * @param body   raw payload string (UTF-8 encoded for HMAC input)
     * @param secret HMAC secret
     * @return full signature header value, e.g. {@code sha256=abc123...}
     */
    static String sign(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + Hex.encodeHexString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA256 signature", e);
        }
    }
}

package com.fooddelivery.payment;

import com.fooddelivery.common.error.ApiException;
import com.fooddelivery.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Verifies `X-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + raw body)>` (Stripe/Razorpay
 * style). The raw bytes are signed, not re-serialised JSON; comparison is constant-time; old timestamps are
 * rejected so a captured request can't be replayed later.
 */
@Component
public class WebhookSignatureVerifier {

    private final byte[] secret;
    private final long toleranceSeconds;
    private final Clock clock;

    public WebhookSignatureVerifier(@Value("${app.payment.webhook.secret}") String secret,
                                    @Value("${app.payment.webhook.tolerance-seconds:300}") long toleranceSeconds,
                                    Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.toleranceSeconds = toleranceSeconds;
        this.clock = clock;
    }

    public void verify(String header, byte[] body) {
        long timestamp;
        String provided;
        try {
            String[] parts = header.split(",");
            timestamp = Long.parseLong(parts[0].trim().substring("t=".length()));
            provided = parts[1].trim().substring("v1=".length());
        } catch (RuntimeException e) {
            throw invalid("Malformed signature header");
        }
        if (Math.abs(clock.instant().getEpochSecond() - timestamp) > toleranceSeconds) {
            throw invalid("Signature timestamp outside the allowed window");
        }
        byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8))) {
            throw invalid("Signature mismatch");
        }
    }

    /** What the gateway computes; public so tests and the README demo can sign payloads. */
    public static String sign(byte[] secret, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_WEBHOOK_SIGNATURE, message) {
        };
    }
}

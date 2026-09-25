package com.fooddelivery.security;

import com.fooddelivery.user.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.WeakKeyException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit test: no Spring context. Time is controlled with a fixed Clock. */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-at-least-32-bytes-long";
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final AuthUser customer = new AuthUser(7L, "c@test.com", Role.CUSTOMER, null, true);

    private static JwtService serviceAt(Instant instant, String secret) {
        return new JwtService(new JwtProperties(secret, 60), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void issuedTokenParsesBackToSameUser() {
        JwtService jwt = serviceAt(NOW, SECRET);

        AuthUser parsed = jwt.parse(jwt.issue(customer));

        assertThat(parsed.id()).isEqualTo(7L);
        assertThat(parsed.email()).isNull(); // email is deliberately not put in the token
        assertThat(parsed.role()).isEqualTo(Role.CUSTOMER);
        assertThat(parsed.getPassword()).isNull();
    }

    @Test
    void payloadContainsNoEmail() {
        String payload = new String(Base64.getUrlDecoder().decode(
                serviceAt(NOW, SECRET).issue(customer).split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(payload).doesNotContain("c@test.com").contains("\"sub\":\"7\"");
    }

    @Test
    void tokenIsRejectedAfterExpiry() {
        String token = serviceAt(NOW, SECRET).issue(customer);

        JwtService oneHourLater = serviceAt(NOW.plus(Duration.ofMinutes(61)), SECRET);

        assertThatThrownBy(() -> oneHourLater.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        JwtService jwt = serviceAt(NOW, SECRET);
        String[] parts = jwt.issue(customer).split("\\.");
        // Attacker rewrites the payload to claim ADMIN but can't re-sign it without the secret.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("CUSTOMER", "ADMIN");
        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        assertThatThrownBy(() -> jwt.parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String foreign = serviceAt(NOW, "a-completely-different-secret-of-32-bytes-plus").issue(customer);

        assertThatThrownBy(() -> serviceAt(NOW, SECRET).parse(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretFailsAtStartup() {
        assertThatThrownBy(() -> serviceAt(NOW, "too-short")).isInstanceOf(WeakKeyException.class);
    }
}

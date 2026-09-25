package com.fooddelivery.security;

import com.fooddelivery.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and verifies HS256-signed access tokens.
 * Claims: sub = user id, role, iat, exp. The payload is only base64-encoded (readable by anyone),
 * the signature just guarantees it wasn't modified — so no sensitive data goes in.
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(JwtProperties props, Clock clock) {
        // Throws WeakKeyException at startup if the secret is shorter than 256 bits: fail fast.
        this.key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofMinutes(props.expirationMinutes());
        this.clock = clock;
    }

    public String issue(AuthUser user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim(CLAIM_ROLE, user.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * @throws io.jsonwebtoken.ExpiredJwtException if expired
     * @throws JwtException for any other invalid token (bad signature, malformed, ...)
     */
    public AuthUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        try {
            return AuthUser.fromToken(
                    Long.valueOf(claims.getSubject()),
                    Role.valueOf(claims.get(CLAIM_ROLE, String.class)));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtException("Token has missing or invalid claims", e);
        }
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }
}

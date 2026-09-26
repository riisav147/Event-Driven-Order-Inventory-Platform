package com.example.platform.shared.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;
    private final long tokenMinutes;

    public JwtService(@Value("${platform.security.jwt-secret}") String secret,
                      @Value("${platform.security.access-token-minutes}") long tokenMinutes) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalArgumentException("JWT secret must be at least 32 bytes");
        key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenMinutes = tokenMinutes;
    }
    public String issue(UUID userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder().subject(userId.toString()).claim("role", role)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(tokenMinutes * 60)))
                .signWith(key).compact();
    }
    public JwtPrincipal parse(String token) {
        var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        return new JwtPrincipal(UUID.fromString(claims.getSubject()), claims.get("role", String.class));
    }
    public record JwtPrincipal(UUID userId, String role) { }
}

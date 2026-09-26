package com.example.platform.shared.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void issuedTokenContainsTheUserAndRole() {
        JwtService service = new JwtService(SECRET, 15);
        UUID userId = UUID.randomUUID();

        JwtService.JwtPrincipal principal = service.parse(service.issue(userId, "CUSTOMER"));

        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void rejectsTamperedToken() {
        JwtService service = new JwtService(SECRET, 15);
        String token = service.issue(UUID.randomUUID(), "CUSTOMER");

        assertThatThrownBy(() -> service.parse(token + "tampered"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsShortSecrets() {
        assertThatThrownBy(() -> new JwtService("too-short", 15))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

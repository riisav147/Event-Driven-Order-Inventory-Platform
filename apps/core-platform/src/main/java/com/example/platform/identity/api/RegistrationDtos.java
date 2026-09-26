package com.example.platform.identity.api;

import java.util.UUID;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public final class RegistrationDtos {
    private RegistrationDtos() { }
    public record RegisterRequest(@NotBlank @Email String email, @NotBlank String password) { }
    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) { }
    public record LoginResponse(String accessToken, String tokenType) { }
    public record UserResponse(UUID id, String email, String role) { }
}

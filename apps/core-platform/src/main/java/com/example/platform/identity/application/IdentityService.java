package com.example.platform.identity.application;

import com.example.platform.identity.api.RegistrationDtos.RegisterRequest;
import com.example.platform.identity.api.RegistrationDtos.UserResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.example.platform.shared.security.JwtService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IdentityService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    public IdentityService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwt) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
    }
    @Transactional
    public UserResponse register(RegisterRequest request) {
        UUID id = UUID.randomUUID();
        String email = request.email().trim().toLowerCase();
        jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, ?)", id, email,
                passwordEncoder.encode(request.password()));
        return new UserResponse(id, email, "CUSTOMER");
    }
    public com.example.platform.identity.api.RegistrationDtos.LoginResponse login(
            com.example.platform.identity.api.RegistrationDtos.LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        var user = jdbc.queryForObject("SELECT id, password_hash, role FROM users WHERE email = ?",
                (rs, row) -> new UserRecord(rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getString("role")), email);
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) throw new IllegalArgumentException("invalid credentials");
        return new com.example.platform.identity.api.RegistrationDtos.LoginResponse(jwt.issue(user.id(), user.role()), "Bearer");
    }
    private record UserRecord(UUID id, String passwordHash, String role) { }
}

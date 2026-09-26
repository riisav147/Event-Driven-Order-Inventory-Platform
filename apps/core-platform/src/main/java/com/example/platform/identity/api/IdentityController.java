package com.example.platform.identity.api;

import com.example.platform.identity.application.IdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.example.platform.identity.api.RegistrationDtos.RegisterRequest;
import static com.example.platform.identity.api.RegistrationDtos.UserResponse;
import static com.example.platform.identity.api.RegistrationDtos.LoginRequest;
import static com.example.platform.identity.api.RegistrationDtos.LoginResponse;

@RestController
@RequestMapping("/api/auth")
public class IdentityController {
    private final IdentityService identity;
    public IdentityController(IdentityService identity) { this.identity = identity; }
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@jakarta.validation.Valid @RequestBody RegisterRequest request) { return identity.register(request); }
    @PostMapping("/login")
    public LoginResponse login(@jakarta.validation.Valid @RequestBody LoginRequest request) { return identity.login(request); }
}

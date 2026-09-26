package com.example.platform.messaging;

// Notification-owned persistence; never loaded by core-platform.

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/dead-letters")
@org.springframework.context.annotation.Profile("notification")
public class DeadLetterController {
    private final DeadLetterService service;
    public DeadLetterController(DeadLetterService service) { this.service = service; }

    @GetMapping public List<Map<String, Object>> list() { return service.list(); }
    @GetMapping("/{id}") public Map<String, Object> get(@PathVariable("id") UUID id) { return service.get(id); }

    @PostMapping("/{id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> replay(@PathVariable("id") UUID id, @Valid @RequestBody ReplayRequest request, Principal principal) {
        return Map.of("outboxId", service.replay(id, principal.getName(), request.reason()));
    }

    public record ReplayRequest(@NotBlank @Size(max = 500) String reason) { }
}

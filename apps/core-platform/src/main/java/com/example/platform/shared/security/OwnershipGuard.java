package com.example.platform.shared.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

public final class OwnershipGuard {
    private OwnershipGuard() { }
    public static void check(UUID requestedOwner) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !requestedOwner.toString().equals(authentication.getName())) {
            throw new AccessDeniedException("resource does not belong to authenticated user");
        }
    }
}

package com.campus.common.security;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;

/**
 * Represents the authenticated user extracted from the security context / JWT.
 */
public record CurrentUser(
    Long id,
    String email,
    Set<String> roles
) {
    public CurrentUser {
        roles = roles == null ? Collections.emptySet() : Collections.unmodifiableSet(roles);
    }

    public boolean hasRole(String role) {
        if (role == null) {
            return false;
        }
        String normalized = role.startsWith("ROLE_") ? role : "ROLE_" + role;
        return roles.contains(role) || roles.contains(normalized);
    }

    public boolean isAdmin() {
        return hasRole("ROLE_ADMIN") || hasRole("ADMIN");
    }
}

package com.campus.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Accessor for the currently authenticated user extracted from the JWT security context.
 */
@Component
public class CurrentUser {

    /**
     * Returns the ID of the current authenticated user from the JWT subject ("sub").
     */
    public Long id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String subject = jwt.getSubject();
            if (subject != null) {
                return Long.parseLong(subject);
            }
        }
        return null;
    }

    /**
     * Checks if the current user has the ADMIN role.
     */
    public boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}

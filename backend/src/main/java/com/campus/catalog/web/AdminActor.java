package com.campus.catalog.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

// Reads the admin's user ID from Matei's JWT "sub" claim for audit logging.
// Once Stefania's CurrentUser bean (#40) merges to main, catalog controllers can delegate to it.
final class AdminActor {

    private AdminActor() {}

    static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            return Long.valueOf(jwt.getSubject());
        }
        return null;
    }
}

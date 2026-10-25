package com.campus.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public record CurrentUser(Long id, String email, boolean isAdmin) {

    public static CurrentUser fromSecurityContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new IllegalStateException("No authenticated JWT found in SecurityContext");
        }

        Long userId = Long.valueOf(jwt.getSubject());
        String email = jwt.getClaimAsString("email");
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> "ROLE_ADMIN".equals(grantedAuthority.getAuthority()));

        return new CurrentUser(userId, email, isAdmin);
    }
}

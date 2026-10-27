package com.campus.common.security;

import com.campus.common.error.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The logged-in user of the current request, read from the JWT that SecurityConfig already verified.
 * Inject it where you need the user id (ownership checks), never take the id from the request body.
 */
@Component
public class CurrentUser {

    /** The "sub" claim: the user id that login put in the token. */
    public Long id() {
        return Long.valueOf(jwt().getSubject());
    }

    /** SecurityConfig turns roles: ["ADMIN"] into the ROLE_ADMIN authority. */
    public boolean isAdmin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    // Only fails on a path that SecurityConfig left public (like /api/v1/auth/**): 401, not a 500.
    private Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new UnauthorizedException("UNAUTHORIZED", "Log in first");
        }
        return jwt;
    }
}

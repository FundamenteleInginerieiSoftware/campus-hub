package com.campus.user.api;

/**
 * What a user is allowed to do. Lives in {@code api}, not {@code domain}, because other modules
 * see it through {@code UserSummary} and may only import {@code user.api}.
 *
 * <p>Goes into the JWT {@code roles} claim; SecurityConfig adds the {@code ROLE_} prefix, so
 * {@code hasRole("ADMIN")} protects {@code /api/v1/admin/**}.
 */
public enum Role {
    STUDENT,
    ADMIN
}

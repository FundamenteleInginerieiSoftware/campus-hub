package com.campus.user.api;

/**
 * What other modules may know about a user. No password hash and no {@code enabled} flag: those stay
 * inside the user module. Returned by {@link UserApi}.
 */
public record UserSummary(Long id, String email, String fullName, Role role) {}

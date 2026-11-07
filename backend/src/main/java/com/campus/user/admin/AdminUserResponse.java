package com.campus.user.admin;

import com.campus.user.api.Role;
import com.campus.user.domain.User;
import java.time.LocalDateTime;

/** A user as the admin pages show it: like UserResponse, plus enabled and createdAt. Never the password hash. */
public record AdminUserResponse(
        Long id, String email, String fullName, Role role, boolean enabled, LocalDateTime createdAt) {

    // Called inside the service's transaction: the entity never reaches the controller.
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt());
    }
}

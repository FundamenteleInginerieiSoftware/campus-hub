package com.campus.user.web;

import com.campus.user.api.Role;
import com.campus.user.domain.User;

/** A user as the API shows it (register, login, GET /users/me). Never the password hash. */
public record UserResponse(Long id, String email, String fullName, Role role) {

    // Call it inside the service's @Transactional method: the entity must not reach the controller.
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}

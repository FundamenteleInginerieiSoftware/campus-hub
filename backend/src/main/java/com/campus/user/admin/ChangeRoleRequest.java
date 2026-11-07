package com.campus.user.admin;

import com.campus.user.api.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of PUT /api/v1/admin/users/{id}/role. An unknown value (e.g. "TEACHER") is 400 MALFORMED_REQUEST. */
public record ChangeRoleRequest(
        @NotNull @Schema(example = "ADMIN") Role role) {}

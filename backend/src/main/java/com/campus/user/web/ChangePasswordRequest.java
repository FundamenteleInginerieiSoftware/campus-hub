package com.campus.user.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;

/**
 * Body of PUT /api/v1/users/me/password. Neither password is trimmed because spaces can be part of
 * a password (same as {@link RegisterRequest}). {@code currentPassword} only checks {@code @NotBlank}
 * here so a wrong value over 72 bytes becomes 401 {@code INVALID_CREDENTIALS} in {@code UserService}
 * rather than 400.
 */
public record ChangePasswordRequest(
        @Schema(example = "oldpass123") @NotBlank String currentPassword,
        // 72 is BCrypt's limit in UTF-8 bytes; multi-byte letters like "ș" are checked below.
        @Schema(example = "newpass123") @NotBlank @Size(min = 8, max = 72)
        String newPassword) {

    // @Size counts Java characters, so 40 x "ș" (80 UTF-8 bytes) passes @Size and would fail inside
    // BCrypt.encode(). Turning it into a validation rule returns 400 VALIDATION_FAILED instead of 500.
    // @JsonIgnore keeps this getter out of JSON and Swagger.
    @JsonIgnore
    @AssertTrue(message = "must be at most 72 bytes (letters like ș count twice)")
    public boolean isNewPasswordWithinBcryptLimit() {
        return newPassword == null || newPassword.getBytes(StandardCharsets.UTF_8).length <= 72;
    }
}

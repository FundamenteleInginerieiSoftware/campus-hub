package com.campus.user.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/v1/auth/register. With {@code @Valid} on the controller parameter, Spring
 * checks these rules before our code runs; a broken rule becomes 400 VALIDATION_FAILED (Liviu's
 * handler). No role field: register always creates a STUDENT, so nobody can make themselves admin.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        // 72 is BCrypt's limit, but in bytes: letters like "ș" take 2 bytes, so a long password full
        // of them can still be too long for BCrypt. Contract rule, still to confirm (api-contract §3).
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String fullName) {}

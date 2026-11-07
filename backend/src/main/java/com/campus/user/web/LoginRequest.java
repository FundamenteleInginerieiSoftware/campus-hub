package com.campus.user.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /api/v1/auth/login. Only "not empty" here: any wrong email or password gets the same
 * 401 INVALID_CREDENTIALS later, so login doesn't hint which rule failed.
 */
// The examples are the dev seed student (UserSeeder), so "Try it out" in Swagger logs in at once.
// That account only exists with the dev profile; in prod the same click gets a 401.
public record LoginRequest(
        @Schema(example = "student1@campus.test") @NotBlank String email,
        @Schema(example = "password") @NotBlank String password) {}

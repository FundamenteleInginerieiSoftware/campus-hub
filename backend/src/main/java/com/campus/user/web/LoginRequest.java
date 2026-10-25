package com.campus.user.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of POST /api/v1/auth/login. Only "not empty" here: any wrong email or password gets the same
 * 401 INVALID_CREDENTIALS later, so login doesn't hint which rule failed.
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}

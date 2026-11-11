package com.campus.user.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

// The pattern is load-bearing: PickupPassService.verify assumes a non-null code of 6 digits.
public record VerifyPickupPassRequest(
        @NotNull Long userId,

        @NotNull @Pattern(regexp = "\\d{6}", message = "code must be exactly 6 digits")
        String code) {}

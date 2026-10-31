package com.campus.catalog.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
        @NotBlank(message = "must not be blank") @Size(max = 100)
        String name,

        @Size(max = 500) String description) {}

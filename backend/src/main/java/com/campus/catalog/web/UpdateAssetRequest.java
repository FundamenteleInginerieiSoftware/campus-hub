package com.campus.catalog.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateAssetRequest(
        @NotBlank(message = "must not be blank") @Size(max = 150)
        String name,

        @NotBlank(message = "must not be blank") @Size(max = 150)
        String model,

        @Size(max = 2000) String description,
        @NotNull(message = "must not be null") Long categoryId,
        @NotNull(message = "must not be null") Long labRoomId) {}

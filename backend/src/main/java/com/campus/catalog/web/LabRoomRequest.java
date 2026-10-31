package com.campus.catalog.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LabRoomRequest(
        @NotBlank(message = "must not be blank") @Size(max = 20)
        String code,

        @NotBlank(message = "must not be blank") @Size(max = 100)
        String name,

        @Size(max = 100) String building) {}

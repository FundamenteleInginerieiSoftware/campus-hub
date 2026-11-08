package com.campus.catalog.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateAssetRequest(
        @Schema(example = "Rigol DS1054Z #1") @NotBlank(message = "must not be blank") @Size(max = 150)
        String name,

        @Schema(example = "Rigol DS1054Z") @NotBlank(message = "must not be blank") @Size(max = 150)
        String model,

        @Schema(example = "DS1ZA2419003") @NotBlank(message = "must not be blank") @Size(max = 100)
        String serialNumber,

        @Schema(example = "4-channel 50 MHz digital oscilloscope") @Size(max = 2000)
        String description,

        @Schema(example = "1") @NotNull(message = "must not be null")
        Long categoryId,

        @Schema(example = "1") @NotNull(message = "must not be null")
        Long labRoomId) {}

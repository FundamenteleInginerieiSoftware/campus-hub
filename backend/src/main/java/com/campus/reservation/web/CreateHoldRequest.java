package com.campus.reservation.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record CreateHoldRequest(
        @NotNull(message = "Asset ID is required") @Schema(description = "ID of the lab asset to hold", example = "1")
        Long assetId,

        @NotNull(message = "Start time is required")
        @Schema(description = "Start time in campus local time", example = "2026-11-10T14:00:00")
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime startTime,

        @NotNull(message = "End time is required")
        @Schema(description = "End time in campus local time", example = "2026-11-10T16:00:00")
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime endTime) {}

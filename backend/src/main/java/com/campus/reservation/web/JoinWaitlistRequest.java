package com.campus.reservation.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record JoinWaitlistRequest(
        @Schema(description = "ID of the asset to wait for", example = "1") @NotNull
        Long assetId,

        @Schema(description = "Desired slot start time (ISO local without offset)", example = "2026-11-10T14:00:00")
        @NotNull
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime startTime,

        @Schema(description = "Desired slot end time (ISO local without offset)", example = "2026-11-10T16:00:00")
        @NotNull
        @JsonFormat(lenient = OptBoolean.FALSE)
        LocalDateTime endTime) {}

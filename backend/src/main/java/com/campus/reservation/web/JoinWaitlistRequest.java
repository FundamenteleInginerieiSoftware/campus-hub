package com.campus.reservation.web;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record JoinWaitlistRequest(
        @NotNull Long assetId,
        @NotNull LocalDateTime startTime,
        @NotNull LocalDateTime endTime) {}

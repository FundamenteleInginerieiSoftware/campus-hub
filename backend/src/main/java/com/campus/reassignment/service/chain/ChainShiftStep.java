package com.campus.reassignment.service.chain;

import java.time.LocalDateTime;

/**
 * Represents a single atomic relocation step in an augmenting chain shift.
 */
public record ChainShiftStep(
        Long reservationId,
        Long userId,
        Long fromAssetId,
        String fromAssetName,
        Long toAssetId,
        String toAssetName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String strategyName) {}

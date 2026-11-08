package com.campus.reservation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * Response payload representing a recurring reservation series and its generated occurrences.
 */
@Schema(description = "Details of a recurring reservation series")
public record RecurringReservationResponse(
        @Schema(
                description = "Unique identifier of the recurring series",
                example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID seriesId,

        @Schema(description = "ID of the reserved asset", example = "1")
        Long assetId,

        @Schema(description = "Total number of scheduled occurrences in this series", example = "4")
        int totalOccurrences,

        @Schema(description = "List of individual reservation occurrences created for the series")
        List<ReservationResponse> occurrences) {}

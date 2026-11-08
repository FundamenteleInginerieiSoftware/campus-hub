package com.campus.reservation.web;

import com.campus.reservation.domain.ReservationTransfer;
import com.campus.reservation.domain.TransferStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * REST response representing a reservation transfer workflow item.
 */
@Schema(description = "Details of a reservation transfer offer")
public record ReservationTransferResponse(
        @Schema(description = "Transfer request ID", example = "1")
        Long id,

        @Schema(description = "ID of the reservation being transferred", example = "10")
        Long reservationId,

        @Schema(description = "User ID of the original owner offering the transfer", example = "1")
        Long fromUserId,

        @Schema(description = "User ID of the intended recipient student", example = "2")
        Long toUserId,

        @Schema(description = "Current status of the transfer", example = "PENDING")
        TransferStatus status,

        @Schema(description = "Timestamp when the transfer was requested", example = "2026-11-09T10:00:00")
        LocalDateTime requestedAt,

        @Schema(
                description = "Timestamp when the transfer was accepted/rejected/cancelled",
                example = "2026-11-09T10:15:00")
        LocalDateTime respondedAt) {

    public static ReservationTransferResponse from(ReservationTransfer t) {
        return new ReservationTransferResponse(
                t.getId(),
                t.getReservationId(),
                t.getFromUserId(),
                t.getToUserId(),
                t.getStatus(),
                t.getRequestedAt(),
                t.getRespondedAt());
    }
}

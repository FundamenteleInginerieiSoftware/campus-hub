package com.campus.reservation.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload to initiate a reservation transfer to another student.
 */
@Schema(description = "Request to initiate transfer of a reservation to another student")
public record InitiateTransferRequest(
        @NotNull(message = "recipientUserId must not be null")
        @Schema(description = "ID of the student to receive the reservation", example = "2")
        Long recipientUserId) {}

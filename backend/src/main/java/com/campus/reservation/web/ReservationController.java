package com.campus.reservation.web;

import com.campus.common.paging.PageResponse;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for reservation endpoints.
 */
@Tag(name = "Reservations", description = "Equipment reservation booking and lifecycle endpoints")
@Validated
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(
            summary = "Create a reservation",
            description = "Books an available asset for a student within valid hours and quotas")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Reservation created successfully"),
        @ApiResponse(responseCode = "400", description = "Validation failed on request body"),
        @ApiResponse(responseCode = "401", description = "Unauthorized (missing or invalid JWT)"),
        @ApiResponse(responseCode = "404", description = "Asset not found"),
        @ApiResponse(responseCode = "409", description = "Booking conflict, quota exceeded, or outside opening hours")
    })
    @PostMapping
    public ResponseEntity<ReservationResponse> create(@Valid @RequestBody CreateReservationRequest request) {
        ReservationResponse response = reservationService.createReservation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Get my reservations",
            description = "Retrieves a paginated list of reservations for the authenticated student")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of reservations returned"),
        @ApiResponse(responseCode = "400", description = "Invalid pagination parameters"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/me")
    public ResponseEntity<PageResponse<ReservationResponse>> getMyReservations(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(reservationService.getMyReservations(status, page, size));
    }

    @Operation(
            summary = "Check equipment availability",
            description =
                    "Checks whether a specific asset or any asset in a category is available for booking in a time window")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Availability evaluation returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Asset not found"),
        @ApiResponse(
                responseCode = "409",
                description =
                        "Invalid time range, alignment, duration, outside opening hours, in past, or missing parameter")
    })
    @GetMapping("/availability")
    public ResponseEntity<AvailabilityResponse> checkAvailability(
            @RequestParam(required = false) Long assetId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return ResponseEntity.ok(reservationService.checkAvailability(assetId, categoryId, startTime, endTime));
    }

    @Operation(
            summary = "Get reservation by ID",
            description = "Retrieves reservation details; non-admins can only retrieve their own reservations")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reservation found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Reservation not found (or belongs to another user)")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ReservationResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.getById(id));
    }

    @Operation(
            summary = "Cancel a reservation",
            description = "Cancels a confirmed reservation; students can cancel only before the start time")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reservation cancelled successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Reservation not found"),
        @ApiResponse(responseCode = "409", description = "Cancellation not allowed or invalid state transition")
    })
    @PostMapping("/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.cancelReservation(id));
    }
}

package com.campus.reservation.web;

import com.campus.common.paging.PageResponse;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
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
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed on request body (VALIDATION_FAILED, MALFORMED_REQUEST)"),
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
        @ApiResponse(
                responseCode = "400",
                description =
                        "Validation failed on query parameters (VALIDATION_FAILED, page < 0, size outside 1-100)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/me")
    public ResponseEntity<PageResponse<ReservationResponse>> getMyReservations(
            @RequestParam(required = false) List<ReservationStatus> status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(reservationService.getMyReservations(status, page, size));
    }

    @Operation(
            summary = "Get my reservations summary stats",
            description = "Retrieves aggregate counts for active, completed, and reassigned bookings")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Summary statistics returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/me/summary")
    public ResponseEntity<ReservationSummaryResponse> getMySummary() {
        return ResponseEntity.ok(reservationService.getMySummary());
    }

    @Operation(
            summary = "Check equipment availability",
            description =
                    "Checks whether a specific asset or any asset in a category is available for booking in a time window")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Availability evaluation returned"),
        @ApiResponse(responseCode = "400", description = "Missing or invalid query parameter (INVALID_PARAMETER)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Asset not found"),
        @ApiResponse(
                responseCode = "409",
                description =
                        "Invalid time range, alignment, duration, outside opening hours, in past, or missing parameter")
    })
    @GetMapping("/availability")
    public ResponseEntity<AvailabilityResponse> checkAvailability(
            @Parameter(description = "ID of the specific asset to check", example = "1") @RequestParam(required = false)
                    Long assetId,
            @Parameter(description = "Category ID to find any available asset within", example = "1")
                    @RequestParam(required = false)
                    Long categoryId,
            @Parameter(
                            description = "Slot start time (must be aligned to :00 or :30, future weekday 09:00-20:00)",
                            example = "2026-11-10T14:00:00")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
                    LocalDateTime startTime,
            @Parameter(
                            description = "Slot end time (must be aligned to :00 or :30, max 4 hours duration)",
                            example = "2026-11-10T16:00:00")
                    @RequestParam
                    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
                    LocalDateTime endTime) {
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

    @Operation(
            summary = "Create simultaneous multi-asset reservation bundle",
            description = "Reserves multiple assets simultaneously in a single atomic transaction; deadlock-free")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Bundle reservations created successfully"),
        @ApiResponse(responseCode = "400", description = "Validation failed, invalid bundle size, or bad time slot"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(
                responseCode = "409",
                description = "Conflict on any asset, asset not bookable, or student quota exceeded")
    })
    @PostMapping("/bundle")
    public ResponseEntity<MultiAssetReservationResponse> createBundle(
            @Valid @RequestBody CreateMultiAssetReservationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reservationService.createMultiAssetReservation(request));
    }

    @Operation(
            summary = "Get multi-asset reservation bundle",
            description = "Retrieves all equipment items in a reservation bundle (owner or admin)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bundle found"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Bundle not found or belongs to another user")
    })
    @GetMapping("/bundle/{bundleId}")
    public ResponseEntity<MultiAssetReservationResponse> getBundle(@PathVariable java.util.UUID bundleId) {
        return ResponseEntity.ok(reservationService.getBundle(bundleId));
    }

    @Operation(
            summary = "Cancel multi-asset reservation bundle",
            description = "Cancels all active reservations in the bundle (owner or admin)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Bundle reservations cancelled"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Bundle not found or belongs to another user")
    })
    @PostMapping("/bundle/{bundleId}/cancel")
    public ResponseEntity<List<ReservationResponse>> cancelBundle(
            @PathVariable java.util.UUID bundleId, @RequestParam(required = false) @Size(max = 255) String reason) {
        return ResponseEntity.ok(reservationService.cancelBundle(bundleId, reason));
    }

    @Operation(
            summary = "Extend an active reservation",
            description = "Extends the end time of a CONFIRMED or CHECKED_OUT reservation atomically")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reservation successfully extended"),
        @ApiResponse(responseCode = "400", description = "Invalid time range or alignment"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Reservation not found"),
        @ApiResponse(
                responseCode = "409",
                description = "Extension conflict on subsequent slot, duration exceeded, or invalid status")
    })
    @PostMapping("/{id}/extend")
    public ResponseEntity<ReservationResponse> extend(
            @PathVariable Long id, @Valid @RequestBody ExtendReservationRequest request) {
        return ResponseEntity.ok(reservationService.extendReservation(id, request));
    }

    @Operation(
            summary = "Initiate a reservation transfer",
            description = "Offers an upcoming confirmed reservation to another student")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Transfer offer initiated successfully"),
        @ApiResponse(responseCode = "400", description = "Validation failed on recipient id"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Reservation not found or recipient user not found"),
        @ApiResponse(responseCode = "409", description = "Cannot transfer to self, already pending, or quota reached")
    })
    @PostMapping("/{id}/transfers")
    public ResponseEntity<ReservationTransferResponse> initiateTransfer(
            @PathVariable Long id, @Valid @RequestBody InitiateTransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reservationService.initiateTransfer(id, request));
    }

    @Operation(
            summary = "Get pending transfer offers",
            description = "Retrieves all incoming pending reservation transfer requests for the student")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of pending transfers"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/transfers/pending")
    public ResponseEntity<List<ReservationTransferResponse>> getPendingTransfers() {
        return ResponseEntity.ok(reservationService.getMyPendingTransfers());
    }

    @Operation(
            summary = "Accept reservation transfer",
            description = "Accepts a pending transfer offer and assumes ownership of the reservation")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transfer accepted and reservation ownership transferred"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Transfer not found"),
        @ApiResponse(responseCode = "409", description = "Active quota exceeded or reservation expired")
    })
    @PostMapping("/transfers/{transferId}/accept")
    public ResponseEntity<ReservationTransferResponse> acceptTransfer(@PathVariable Long transferId) {
        return ResponseEntity.ok(reservationService.acceptTransfer(transferId));
    }

    @Operation(summary = "Reject reservation transfer", description = "Declines an incoming reservation transfer offer")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transfer rejected"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Transfer not found"),
        @ApiResponse(responseCode = "409", description = "Invalid transfer status transition")
    })
    @PostMapping("/transfers/{transferId}/reject")
    public ResponseEntity<ReservationTransferResponse> rejectTransfer(@PathVariable Long transferId) {
        return ResponseEntity.ok(reservationService.rejectTransfer(transferId));
    }

    @Operation(
            summary = "Cancel reservation transfer offer",
            description = "Cancels an outgoing reservation transfer offer by the original sender")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Transfer cancelled"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Transfer not found"),
        @ApiResponse(responseCode = "409", description = "Invalid transfer status transition")
    })
    @PostMapping("/transfers/{transferId}/cancel")
    public ResponseEntity<ReservationTransferResponse> cancelTransfer(@PathVariable Long transferId) {
        return ResponseEntity.ok(reservationService.cancelTransfer(transferId));
    }
}

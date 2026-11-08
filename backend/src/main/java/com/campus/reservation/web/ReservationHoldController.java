package com.campus.reservation.web;

import com.campus.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for managing temporary reservation holds (slot locking).
 */
@RestController
@RequestMapping("/api/v1/reservations/holds")
@RequiredArgsConstructor
@Tag(
        name = "Reservation Holds",
        description = "Temporary slot locking for smooth booking UX and concurrency protection")
public class ReservationHoldController {

    private final ReservationService reservationService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Acquire a temporary 1-minute hold on a lab asset slot")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Hold acquired successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid time slot, duration, or alignment"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(
                responseCode = "409",
                description = "Slot already reserved, held by another user, or active hold quota exceeded")
    })
    public ReservationHoldResponse createHold(@Valid @RequestBody CreateHoldRequest request) {
        return reservationService.createHold(request);
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "Confirm an active hold and convert it into a confirmed reservation")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Reservation confirmed"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "404", description = "Hold not found or belongs to another user"),
        @ApiResponse(
                responseCode = "409",
                description = "Hold expired, already confirmed/released, or student quota reached")
    })
    public ReservationResponse confirmHold(@PathVariable Long id) {
        return reservationService.confirmHold(id);
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "Release an active hold early and free the slot for other students")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Hold released successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "404", description = "Hold not found or belongs to another user"),
        @ApiResponse(responseCode = "409", description = "Hold is not in ACTIVE status")
    })
    public ReservationHoldResponse releaseHold(@PathVariable Long id) {
        return reservationService.releaseHold(id);
    }

    @GetMapping("/active")
    @Operation(summary = "List current user's active unexpired reservation holds")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of active holds with remaining seconds"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    public List<ReservationHoldResponse> getMyActiveHolds() {
        return reservationService.getMyActiveHolds();
    }
}

package com.campus.reservation.web;

import com.campus.reservation.service.WaitlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the FIFO reservation waitlist and admin-triggered slot promotion.
 */
@Tag(name = "Reservation Waitlist", description = "FIFO waitlist with automatic promotion when slots open up")
@RestController
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    @Operation(
            summary = "Join waitlist for an occupied slot",
            description = "Adds the authenticated student to the FIFO waitlist for a currently booked slot")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Added to waitlist"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(
                responseCode = "409",
                description = "Slot is already free, violates slot rules, or user is already on waitlist")
    })
    @PostMapping("/api/v1/reservations/waitlist")
    public ResponseEntity<WaitlistEntryResponse> join(@Valid @RequestBody CreateReservationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(waitlistService.joinWaitlist(request));
    }

    @Operation(summary = "List my waitlist entries", description = "Returns all waitlist entries for the current user")
    @GetMapping("/api/v1/reservations/waitlist/me")
    public ResponseEntity<List<WaitlistEntryResponse>> getMyWaitlist() {
        return ResponseEntity.ok(waitlistService.getMyWaitlist());
    }

    @Operation(summary = "Cancel a waitlist entry", description = "Cancels a WAITING entry owned by the current user")
    @PostMapping("/api/v1/reservations/waitlist/{id}/cancel")
    public ResponseEntity<WaitlistEntryResponse> cancel(@PathVariable Long id) {
        return ResponseEntity.ok(waitlistService.cancelWaitlistEntry(id));
    }

    @Operation(
            summary = "Trigger FIFO auto-promotion for an asset (Admin)",
            description =
                    "Admin endpoint under /api/v1/admin/** that locks the asset and promotes eligible waitlisted students")
    @PostMapping("/api/v1/admin/reservations/waitlist/assets/{assetId}/promote")
    public ResponseEntity<List<WaitlistEntryResponse>> promoteForAsset(@PathVariable Long assetId) {
        return ResponseEntity.ok(waitlistService.processPromotionsForAsset(assetId));
    }
}

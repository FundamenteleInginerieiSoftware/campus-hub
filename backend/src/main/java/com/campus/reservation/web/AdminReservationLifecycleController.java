package com.campus.reservation.web;

import com.campus.reservation.service.ReservationLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin desk endpoints for equipment check-out/return and triggering autonomous lifecycle sweeps.
 * Protected under {@code /api/v1/admin/**} by {@code SecurityConfig}.
 */
@Tag(
        name = "Admin Reservation Desk & Lifecycle",
        description = "Check-out, return, and automated no-show / overdue cascade jobs")
@RestController
@RequestMapping("/api/v1/admin/reservations")
@RequiredArgsConstructor
public class AdminReservationLifecycleController {

    private final ReservationLifecycleService lifecycleService;

    @Operation(
            summary = "Check out equipment for a confirmed reservation",
            description = "Transitions reservation to CHECKED_OUT if within [-15m, +30m] of start time")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Checked out successfully"),
        @ApiResponse(responseCode = "404", description = "Reservation not found"),
        @ApiResponse(responseCode = "409", description = "Outside check-out window or invalid state transition")
    })
    @PostMapping("/{id}/check-out")
    public ResponseEntity<ReservationResponse> checkOut(@PathVariable Long id) {
        return ResponseEntity.ok(lifecycleService.checkOut(id));
    }

    @Operation(
            summary = "Return equipment for a checked-out or overdue reservation",
            description = "Transitions reservation to RETURNED and records return timestamp")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Returned successfully"),
        @ApiResponse(responseCode = "404", description = "Reservation not found"),
        @ApiResponse(responseCode = "409", description = "Invalid state transition")
    })
    @PostMapping("/{id}/return")
    public ResponseEntity<ReservationResponse> returnAsset(@PathVariable Long id) {
        return ResponseEntity.ok(lifecycleService.returnAsset(id));
    }

    @Operation(
            summary = "Run automated no-show and overdue cascade sweep",
            description = "Executes the scheduled lifecycle job on demand and returns a summary of transitions")
    @PostMapping("/jobs/run-lifecycle")
    public ResponseEntity<LifecycleSweepSummary> runLifecycleSweep() {
        return ResponseEntity.ok(lifecycleService.runLifecycleSweep());
    }
}

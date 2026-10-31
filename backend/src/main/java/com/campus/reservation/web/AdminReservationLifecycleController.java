package com.campus.reservation.web;

import com.campus.reservation.service.ReservationLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin lifecycle jobs endpoint for triggering autonomous reservation sweeps.
 * Protected under {@code /api/v1/admin/**} by {@code SecurityConfig}.
 */
@Tag(
        name = "Admin Reservation Lifecycle",
        description = "Automated and on-demand no-show release and overdue cascade jobs")
@RestController
@RequestMapping("/api/v1/admin/reservations/jobs")
@RequiredArgsConstructor
public class AdminReservationLifecycleController {

    private final ReservationLifecycleService lifecycleService;

    @Operation(
            summary = "Run automated no-show and overdue cascade sweep",
            description = "Executes the scheduled lifecycle job on demand and returns a summary of transitions")
    @PostMapping("/run-lifecycle")
    public ResponseEntity<LifecycleSweepSummary> runLifecycleSweep() {
        return ResponseEntity.ok(lifecycleService.runLifecycleSweep());
    }
}

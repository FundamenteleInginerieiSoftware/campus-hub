package com.campus.reservation.web;

import com.campus.common.paging.PageResponse;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for admin desk reservation operations (search, check-out, return).
 *
 * <p>Protected under {@code /api/v1/admin/**} by SecurityConfig requiring ADMIN role.
 */
@Tag(
        name = "Admin Reservations",
        description = "Admin desk operations for equipment check-out, return, and inventory management")
@Validated
@RestController
@RequestMapping("/api/v1/admin/reservations")
@RequiredArgsConstructor
public class AdminReservationController {

    private final ReservationService reservationService;

    @Operation(
            summary = "Search reservations (Admin)",
            description = "Retrieves a paginated list of reservations filtered by status, user, asset, or date")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of reservations returned"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden (requires ADMIN role)")
    })
    @GetMapping
    public ResponseEntity<PageResponse<ReservationResponse>> search(
            @RequestParam(required = false) ReservationStatus status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long assetId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(reservationService.getAdminReservations(status, userId, assetId, date, page, size));
    }

    @Operation(
            summary = "Check out reservation",
            description = "Marks a confirmed reservation as checked out at the lab desk")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Equipment checked out successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden (requires ADMIN role)"),
        @ApiResponse(responseCode = "404", description = "Reservation not found"),
        @ApiResponse(responseCode = "409", description = "Check-out window closed or invalid reservation state")
    })
    @PostMapping("/{id}/check-out")
    public ResponseEntity<ReservationResponse> checkOut(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.checkOutReservation(id));
    }

    @Operation(summary = "Return reservation", description = "Marks a checked-out or overdue reservation as returned")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Equipment returned successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden (requires ADMIN role)"),
        @ApiResponse(responseCode = "404", description = "Reservation not found"),
        @ApiResponse(responseCode = "409", description = "Invalid reservation state transition")
    })
    @PostMapping("/{id}/return")
    public ResponseEntity<ReservationResponse> returnItem(@PathVariable Long id) {
        return ResponseEntity.ok(reservationService.returnReservation(id));
    }
}

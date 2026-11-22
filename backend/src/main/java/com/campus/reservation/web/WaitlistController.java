package com.campus.reservation.web;

import com.campus.common.paging.PageResponse;
import com.campus.reservation.service.WaitlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the student FIFO waitlist on occupied equipment slots (#59).
 */
@Tag(name = "Reservation Waitlist", description = "FIFO waitlist for occupied equipment slots")
@Validated
@RestController
@RequestMapping("/api/v1/reservations/waitlist")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    @Operation(
            summary = "Join FIFO waitlist for an occupied slot",
            description = "Queues the authenticated student for a slot that is currently booked or held")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Joined waitlist"),
        @ApiResponse(
                responseCode = "400",
                description = "Validation failed or malformed request (VALIDATION_FAILED, MALFORMED_REQUEST)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Asset not found"),
        @ApiResponse(
                responseCode = "409",
                description =
                        "SLOT_ALREADY_FREE, WAITLIST_ALREADY_JOINED, WAITLIST_LIMIT_REACHED, OWN_RESERVATION_CONFLICT, ASSET_NOT_BOOKABLE, or slot rule error")
    })
    @PostMapping
    public ResponseEntity<WaitlistEntryResponse> join(@Valid @RequestBody JoinWaitlistRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(waitlistService.joinWaitlist(request));
    }

    @Operation(summary = "Get my waitlist entries", description = "Returns paginated waitlist entries newest first")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of waitlist entries returned"),
        @ApiResponse(responseCode = "400", description = "Invalid pagination parameters (VALIDATION_FAILED)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/me")
    public ResponseEntity<PageResponse<WaitlistEntryResponse>> getMyWaitlist(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(waitlistService.getMyWaitlist(page, size));
    }

    @Operation(
            summary = "Cancel a waitlist entry",
            description = "Cancels a WAITING entry owned by the caller (or any entry for an admin)")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Waitlist entry cancelled"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Waitlist entry not found (WAITLIST_ENTRY_NOT_FOUND)"),
        @ApiResponse(responseCode = "409", description = "Entry is not in WAITING status")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable Long id) {
        waitlistService.cancelWaitlistEntry(id);
        return ResponseEntity.noContent().build();
    }
}

package com.campus.reservation.web;

import com.campus.reservation.service.CalendarTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authenticated settings endpoints for the logged-in user's private iCalendar subscription link.
 */
@RestController
@RequestMapping("/api/v1/reservations/me/calendar-feed")
@RequiredArgsConstructor
@Tag(name = "Calendar feed", description = "Manage the logged-in student's private iCalendar subscription link")
public class CalendarFeedSettingsController {

    private final CalendarTokenService calendarTokenService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Check whether the logged-in user has an active calendar link")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Calendar feed status"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    public CalendarFeedStatusResponse status() {
        return calendarTokenService.status();
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create or replace the logged-in user's private calendar link")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Calendar link created (returned once)"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    public CalendarFeedCreatedResponse create() {
        return calendarTokenService.create();
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Turn off and revoke the logged-in user's private calendar link")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Calendar link revoked"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated")
    })
    public void revoke() {
        calendarTokenService.revoke();
    }
}

package com.campus.reservation.web;

import com.campus.reservation.service.CalendarFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public iCalendar subscription endpoint keyed by a per-user 256-bit secret token.
 */
@RestController
@RequestMapping("/api/v1/calendar")
@RequiredArgsConstructor
@Tag(name = "Calendar feed", description = "Subscribe to student reservations iCalendar feed")
public class CalendarFeedController {

    private static final String TEXT_CALENDAR_UTF8_VALUE = "text/calendar;charset=UTF-8";
    private static final MediaType TEXT_CALENDAR_UTF8 = MediaType.parseMediaType(TEXT_CALENDAR_UTF8_VALUE);

    private final CalendarFeedService calendarFeedService;

    @GetMapping(value = "/{token}.ics", produces = TEXT_CALENDAR_UTF8_VALUE)
    @Operation(summary = "Subscribe to student reservations iCalendar feed")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "iCalendar feed containing the student's reservations",
                content = @Content(mediaType = TEXT_CALENDAR_UTF8_VALUE, schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "404", description = "Calendar link not found (CALENDAR_FEED_NOT_FOUND)")
    })
    public ResponseEntity<String> feed(@PathVariable("token") String token) {
        String ics = calendarFeedService.feedFor(token);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(TEXT_CALENDAR_UTF8)
                .body(ics);
    }
}

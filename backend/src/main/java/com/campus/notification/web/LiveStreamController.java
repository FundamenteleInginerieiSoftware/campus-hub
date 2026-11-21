package com.campus.notification.web;

import com.campus.common.error.UnauthorizedException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.service.LiveStreams;
import com.campus.user.api.UserApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Exposes the Server-Sent Events (SSE) stream for live browser notifications.
 *
 * <p>Protected by the normal JWT filter in {@code SecurityConfig}: browsers open the stream with
 * {@code fetch} and the {@code Authorization: Bearer ...} header (never passing tokens in URLs).
 */
@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
@Tag(name = "Live updates", description = "Server-Sent Events stream for real-time user notifications")
public class LiveStreamController {

    private final LiveStreams liveStreams;
    private final CurrentUser currentUser;
    private final UserApi userApi;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Open a live notification stream (SSE)",
            description =
                    "Opens a long-lived Server-Sent Events response (text/event-stream) for the logged-in user. "
                            + "The server keeps the response open for up to 10 minutes, sends ':hb' heartbeats every 20 seconds, "
                            + "and emits 'notification' events as JSON payloads. At most 5 concurrent streams per user are allowed.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SSE stream opened (stays open)"),
        @ApiResponse(responseCode = "401", description = "Unauthorized (missing or invalid JWT)"),
        @ApiResponse(responseCode = "409", description = "Too many open tabs for this account (LIVE_STREAM_LIMIT)")
    })
    public SseEmitter stream(HttpServletResponse response) {
        Long userId = currentUser.id();
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        SseEmitter emitter = liveStreams.open(userId);
        // Close the tiny race where an admin disables the account right after the JWT filter
        // checked it and before liveStreams.open() registered this emitter (so the disable
        // listener already ran). Re-checking after open() ensures no disabled user keeps a stream.
        if (userApi.findActiveRole(userId).isEmpty()) {
            liveStreams.completeAll(userId);
            throw new UnauthorizedException("Account is disabled");
        }
        return emitter;
    }
}

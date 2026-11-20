package com.campus.notification.web;

import java.time.LocalDateTime;

/**
 * Payload pushed over the Server-Sent Events stream ({@code GET /api/v1/live/stream}).
 *
 * <p>{@code type} is always {@code "NOTIFICATION"} in v1 so clients can distinguish future event
 * kinds without breaking parsing. {@code at} is campus local time (Europe/Bucharest), without offset.
 */
public record LiveEvent(String type, String title, String message, LocalDateTime at) {

    public static final String TYPE_NOTIFICATION = "NOTIFICATION";

    public static LiveEvent notification(String title, String message, LocalDateTime at) {
        return new LiveEvent(TYPE_NOTIFICATION, title, message, at);
    }
}

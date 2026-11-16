package com.campus.reservation.service;

import java.time.Instant;

/**
 * Pure value object representing a single {@code VEVENT} entry to be serialized by {@link IcsWriter}.
 */
public record CalendarEvent(String uid, Instant start, Instant end, String summary, String description) {}

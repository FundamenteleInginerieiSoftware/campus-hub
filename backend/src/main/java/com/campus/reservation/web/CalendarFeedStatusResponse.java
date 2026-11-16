package com.campus.reservation.web;

/**
 * Reports whether the logged-in user currently has an active iCalendar subscription link.
 */
public record CalendarFeedStatusResponse(boolean enabled) {}

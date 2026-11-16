package com.campus.reservation.web;

/**
 * Returns the newly generated private iCalendar path once at creation time; only its SHA-256 hash
 * is stored on the server.
 */
public record CalendarFeedCreatedResponse(String path) {}

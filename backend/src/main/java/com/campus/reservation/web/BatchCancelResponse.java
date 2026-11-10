package com.campus.reservation.web;

/**
 * Summary response returned when cancelling multiple reservations in bulk
 * (e.g., when an administrator cancels upcoming reservations for a disabled user).
 *
 * @param count the number of reservations that were cancelled
 * @param message informative status message
 */
public record BatchCancelResponse(int count, String message) {}

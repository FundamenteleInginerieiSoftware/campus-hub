package com.campus.reservation.web;

/**
 * Single cell in the demand heatmap grid representing booking intensity for a specific day and hour.
 *
 * @param dayOfWeek ISO-8601 day of week (1 = Monday, 2 = Tuesday, ..., 7 = Sunday)
 * @param hour hour of the day (8 to 19, e.g. 14 represents 14:00-15:00)
 * @param bookingCount number of active reservations spanning this hour slot
 * @param intensityScore normalized demand intensity between 0.0 (idle) and 1.0 (peak rush hour)
 */
public record HeatmapCell(int dayOfWeek, int hour, int bookingCount, double intensityScore) {}

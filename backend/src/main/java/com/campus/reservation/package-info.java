/**
 * Reservations and scheduling: booking, conflict check, limits, checkout/return, availability and
 * the overdue/no-show jobs.
 *
 * <p>Owner: Calin Murariu (GitHub @Swiorx).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public facade ({@code ReservationApi}) and {@code ReservationSummary}. The only
 *       package other modules may import.
 *   <li>{@code domain}: entities and enums.
 *   <li>{@code repository}: Spring Data JPA interfaces.
 *   <li>{@code service}: business logic, implements the api facade.
 *   <li>{@code web}: REST controllers and request/response records.
 * </ul>
 *
 * <p>May depend on: {@code common}, {@code user.api}, {@code catalog.api}, storage, notification.
 * Used by reassignment (and optionally incident) through {@code reservation.api}. Must NOT depend on
 * incident, reassignment.
 */
package com.campus.reservation;

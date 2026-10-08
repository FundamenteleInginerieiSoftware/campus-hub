/**
 * Incident impact and auto-reassignment engine: moves bookings of a damaged asset to a substitute
 * (Strategy pattern) or force-cancels them, and logs the outcome.
 *
 * <p>Owner: Stefania (GitHub @m1runa-stefan1a).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public types (if any). The only package other modules may import.
 *   <li>{@code domain}: entities and enums (reassignment log).
 *   <li>{@code repository}: Spring Data JPA interfaces.
 *   <li>{@code service}: the engine, substitute strategies and the event listener.
 *   <li>{@code web}: REST controllers and request/response records.
 * </ul>
 *
 * <p>May depend on: {@code common}, {@code user.api}, {@code catalog.api}, {@code reservation.api},
 * {@code incident.api} events, storage, notification. Business modules must NOT depend on it.
 */
package com.campus.reassignment;

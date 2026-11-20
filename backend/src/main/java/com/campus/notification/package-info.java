/**
 * User notifications: logged to the console in dev, sent by e-mail (Amazon SES) in prod.
 *
 * <p>Owners: Liviu Nedelcu (GitHub @NliviuN) for the interface and {@code
 * LoggingNotificationService}; Matei Necula (GitHub @matei-necula) for {@code SesNotificationService}.
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public interface ({@code NotificationService}). The only package other modules
 *       may import.
 *   <li>{@code domain}: entities and enums (if any).
 *   <li>{@code repository}: Spring Data JPA interfaces (if any).
 *   <li>{@code service}: implementations ({@code LoggingNotificationService}, {@code
 *       SesNotificationService}) and the in-memory live SSE stream registry ({@code LiveStreams}).
 *   <li>{@code web}: REST controllers and response records ({@code LiveStreamController}, {@code
 *       LiveEvent}).
 * </ul>
 *
 * <p>May depend on: {@code common}, {@code user.api}. Used by incident, reassignment, reservation.
 * Must NOT depend on catalog, reservation, incident, reassignment.
 */
package com.campus.notification;

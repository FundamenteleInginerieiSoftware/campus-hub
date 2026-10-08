/**
 * User notifications: logged to the console in dev, sent by e-mail (Amazon SES) in prod.
 *
 * <p>Owner: Matei Necula (GitHub @matei-necula).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public interface ({@code NotificationService}). The only package other modules
 *       may import.
 *   <li>{@code domain}: entities and enums (if any).
 *   <li>{@code repository}: Spring Data JPA interfaces (if any).
 *   <li>{@code service}: implementations ({@code LoggingNotificationService}, {@code
 *       SesNotificationService}).
 *   <li>{@code web}: REST controllers and request/response records (if any).
 * </ul>
 *
 * <p>May depend on: {@code common}, {@code user.api}. Used by incident, reassignment, reservation.
 * Must NOT depend on catalog, reservation, incident, reassignment.
 */
package com.campus.notification;

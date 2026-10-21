/**
 * Incident hub: report broken equipment (with photo), lock the asset, admin triage and resolve.
 *
 * <p>Owner: Stefania (GitHub @m1runa-stefan1a).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public events ({@code AssetDamagedEvent}). The only package other modules may
 *       import.
 *   <li>{@code domain}: entities and enums.
 *   <li>{@code repository}: Spring Data JPA interfaces.
 *   <li>{@code service}: business logic, publishes the events.
 *   <li>{@code web}: REST controllers and request/response records.
 * </ul>
 *
 * <p>May depend on: {@code common}, {@code user.api}, {@code catalog.api}, optionally {@code
 * reservation.api}, storage, notification. Used by reassignment through {@code incident.api}
 * events. Must NOT depend on reassignment.
 */
package com.campus.incident;

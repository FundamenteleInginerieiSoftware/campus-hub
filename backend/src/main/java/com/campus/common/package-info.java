/**
 * Shared building blocks used by every module: errors, time, audit, paging and the current user.
 *
 * <p>Owner: Matei Necula (GitHub @matei-necula).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code error}: shared exceptions and the global {@code ProblemDetail} handler.
 *   <li>{@code time}: the shared {@code Clock} bean (Europe/Bucharest).
 *   <li>{@code audit}: {@code AuditService} and the audit log.
 *   <li>{@code paging}: the {@code PageResponse} record for paginated lists.
 *   <li>{@code security}: {@code CurrentUser} (reads the JWT of the current request).
 * </ul>
 *
 * <p>Every module may use it. Must NOT depend on any business module (user, catalog, reservation,
 * incident, reassignment).
 */
package com.campus.common;

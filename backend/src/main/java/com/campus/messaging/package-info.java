/**
 * Amazon SQS bridge for the incident to reassignment event (prod profile only).
 *
 * <p>Owner: Matei Necula (GitHub @matei-necula).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public types (if any). The only package other modules may import.
 *   <li>{@code domain}: entities and enums (if any).
 *   <li>{@code repository}: Spring Data JPA interfaces (if any).
 *   <li>{@code service}: {@code SqsBridge} (sends {@code AssetDamagedEvent} after commit) and {@code
 *       SqsConsumer} (hands it to reassignment).
 *   <li>{@code web}: REST controllers (if any).
 * </ul>
 *
 * <p>May depend on: {@code common}, {@code incident.api}, {@code reassignment.api}. No module
 * depends on it: incident does not know whether SQS exists.
 */
package com.campus.messaging;

/**
 * File storage for incident photos: local disk in dev, Amazon S3 in prod.
 *
 * <p>Owner: Matei Necula (GitHub @matei-necula).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public interface ({@code StorageService}). The only package other modules may
 *       import.
 *   <li>{@code domain}: entities and enums (if any).
 *   <li>{@code repository}: Spring Data JPA interfaces (if any).
 *   <li>{@code service}: implementations ({@code LocalStorageService}, {@code S3StorageService}).
 *   <li>{@code web}: REST controllers and request/response records (if any).
 * </ul>
 *
 * <p>May depend on: {@code common}. Used by incident, reassignment, reservation. Must NOT depend on
 * any business module.
 */
package com.campus.storage;

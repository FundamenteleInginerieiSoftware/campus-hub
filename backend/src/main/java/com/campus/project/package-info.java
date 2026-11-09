/**
 * Project assistant module (#140): bilingual project library, search, and kit availability.
 *
 * <p>Owner: Matei Necula (GitHub @matei-necula).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code domain}: immutable project records and enums loaded from classpath JSON.
 *   <li>{@code service}: {@code ProjectLibrary}, {@code ProjectSearch}, and {@code ProjectService}.
 *   <li>{@code web}: {@code ProjectController} and response records.
 * </ul>
 *
 * <p>May depend on: {@code common}, {@code catalog.api}, {@code reservation.api}. Leaf module: no
 * other module depends on {@code project}.
 */
package com.campus.project;

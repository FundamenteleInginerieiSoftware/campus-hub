/**
 * Asset catalog and inventory: assets, categories, lab rooms, asset status, search and locking.
 *
 * <p>Owner: Liviu Nedelcu (GitHub @NliviuN).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public facade ({@code CatalogApi}) and {@code AssetSummary}. The only package
 *       other modules may import.
 *   <li>{@code domain}: entities and enums.
 *   <li>{@code repository}: Spring Data JPA interfaces.
 *   <li>{@code service}: business logic, implements the api facade.
 *   <li>{@code web}: REST controllers and request/response records.
 * </ul>
 *
 * <p>May depend on: {@code common}. Used by reservation, incident, reassignment through {@code
 * catalog.api}. Must NOT depend on reservation, incident, reassignment.
 */
package com.campus.catalog;

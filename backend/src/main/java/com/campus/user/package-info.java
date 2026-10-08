/**
 * Users and authentication: register, login, JWT issuing, roles (STUDENT, ADMIN).
 *
 * <p>Owner: Matei Necula (GitHub @matei-necula).
 *
 * <p>Sub-packages:
 *
 * <ul>
 *   <li>{@code api}: public facade ({@code UserApi}) and {@code UserSummary}. The only package
 *       other modules may import.
 *   <li>{@code domain}: entities and enums.
 *   <li>{@code repository}: Spring Data JPA interfaces.
 *   <li>{@code service}: business logic, implements the api facade.
 *   <li>{@code web}: REST controllers and request/response records.
 * </ul>
 *
 * <p>May depend on: {@code common}, {@code config}. Used by reservation, incident, reassignment
 * through {@code user.api}. Must NOT depend on catalog, reservation, incident, reassignment.
 */
package com.campus.user;

/**
 * Application-wide Spring configuration: security, OpenAPI (Swagger) and async events.
 *
 * <p>Owner: Matei Necula (GitHub @matei-necula).
 *
 * <p>Classes:
 *
 * <ul>
 *   <li>{@code SecurityConfig}: JWT resource server and URL rules ({@code /api/v1/admin/**} is
 *       admin only).
 *   <li>{@code OpenApiConfig}: Swagger UI and bearer-token auth.
 *   <li>{@code AsyncConfig}: thread pool for asynchronous event listeners.
 * </ul>
 *
 * <p>Every module may use it. May depend on {@code common}. Must NOT depend on business modules.
 */
package com.campus.config;

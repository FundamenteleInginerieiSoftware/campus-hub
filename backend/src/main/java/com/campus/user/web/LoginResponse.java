package com.campus.user.web;

/**
 * What login returns. The frontend keeps {@code accessToken} and sends it as "Authorization: Bearer
 * ..." on every call. {@code expiresIn} is in seconds (28800 = 8 h, app.jwt.expiration), so the
 * frontend knows when to send the user back to login.
 */
public record LoginResponse(String accessToken, long expiresIn, UserResponse user) {}

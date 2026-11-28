package com.campus.user.web;

import com.campus.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Needs a token: SecurityConfig's /api/v1/** rule answers 401 before this controller is reached. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "The logged-in user's own account")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "The account of the token's user")
    @ApiResponse(responseCode = "200", description = "The logged-in user")
    public UserResponse me() {
        return userService.me();
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Change the logged-in user's password",
            description =
                    "Verifies the current password, updates the hash, and writes a USER_PASSWORD_CHANGED audit entry.")
    @ApiResponse(responseCode = "204", description = "Password changed")
    @ApiResponse(
            responseCode = "400",
            description =
                    "Missing fields, new password outside 8–72 characters, or over 72 UTF-8 bytes (VALIDATION_FAILED)")
    @ApiResponse(
            responseCode = "401",
            description = "Missing/invalid token (UNAUTHORIZED) or wrong current password (INVALID_CREDENTIALS)")
    @ApiResponse(responseCode = "409", description = "New password matches the current password (PASSWORD_UNCHANGED)")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request);
    }
}

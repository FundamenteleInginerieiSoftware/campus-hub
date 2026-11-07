package com.campus.user.web;

import com.campus.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public endpoints: SecurityConfig permits /api/v1/auth/** without a token. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Register, log in and get a token")
public class AuthController {

    private final AuthService authService;

    @GetMapping("/ping")
    @Operation(summary = "Check that the backend runs")
    public PingResponse ping() {
        return new PingResponse("pong");
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a student account")
    @ApiResponse(responseCode = "201", description = "Account created, always with role STUDENT")
    @ApiResponse(responseCode = "400", description = "A field breaks a rule (VALIDATION_FAILED)")
    @ApiResponse(responseCode = "409", description = "The email is already used (EMAIL_TAKEN)")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in; copy accessToken into Swagger's Authorize button")
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "400", description = "Email or password is empty (VALIDATION_FAILED)")
    @ApiResponse(
            responseCode = "401",
            description = "Wrong email or password, or a disabled account (INVALID_CREDENTIALS)")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    // Spring turns the record into JSON automatically: {"message":"pong"}
    public record PingResponse(String message) {}
}

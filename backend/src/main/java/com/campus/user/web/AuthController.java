package com.campus.user.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login and register land here in week 3. For now, only a public ping: everyone runs the app and
 * calls it to check that their setup works. It's public because SecurityConfig permits /api/v1/auth/**.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("pong");
    }

    // Spring turns the record into JSON automatically: {"message":"pong"}
    public record PingResponse(String message) {}
}

package com.campus.user.web;

import com.campus.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Needs a token: SecurityConfig's /api/v1/** rule answers 401 before this controller is reached. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Users", description = "The logged-in user's own account")
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    @Operation(summary = "The account of the token's user (401 without a token)")
    public UserResponse me() {
        return userService.me();
    }
}

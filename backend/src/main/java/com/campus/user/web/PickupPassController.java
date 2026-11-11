package com.campus.user.web;

import com.campus.user.service.PickupPassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Needs a token: SecurityConfig's /api/v1/** rule answers 401 before this controller is reached. */
@RestController
@RequestMapping("/api/v1/users/me/pickup-pass")
@RequiredArgsConstructor
@Tag(name = "Pickup pass", description = "The rotating code a student shows at the lab desk")
public class PickupPassController {

    private final PickupPassService pickupPassService;

    @GetMapping
    @Operation(
            summary = "The logged-in student's pickup pass",
            description =
                    "A 6-digit code that changes every 30 seconds, the text for a QR image and the seconds left. The user comes from the token.")
    @ApiResponse(responseCode = "200", description = "The pass right now")
    public PickupPassResponse currentPass() {
        return pickupPassService.currentPass();
    }
}

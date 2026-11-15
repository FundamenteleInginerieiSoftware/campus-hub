package com.campus.user.web;

import com.campus.user.service.PickupPassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin only: the /api/v1/admin/** rule in SecurityConfig is what keeps students out (403 before this
 * controller is reached). The desk page calls this, then Calin's check-out / return as before.
 */
@RestController
@RequestMapping("/api/v1/admin/pickup-pass")
@RequiredArgsConstructor
@Tag(name = "Pickup pass", description = "The rotating code a student shows at the lab desk")
public class AdminPickupPassController {

    private final PickupPassService pickupPassService;

    @PostMapping("/verify")
    @Operation(
            summary = "Verify the code a student shows at the desk",
            description =
                    "The code of the current or the previous 30-second step is accepted. Success is written to the audit log (PICKUP_PASS_VERIFIED).")
    @ApiResponse(responseCode = "200", description = "The code is right: who the student is")
    @ApiResponse(responseCode = "400", description = "userId missing or code is not 6 digits (VALIDATION_FAILED)")
    @ApiResponse(
            responseCode = "409",
            description = "Wrong or expired code, unknown or disabled user (PICKUP_CODE_INVALID)")
    public PickupPassVerifiedResponse verify(@Valid @RequestBody VerifyPickupPassRequest request) {
        return pickupPassService.verify(request.userId(), request.code());
    }
}

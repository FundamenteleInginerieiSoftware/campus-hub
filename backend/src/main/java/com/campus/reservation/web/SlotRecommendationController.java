package com.campus.reservation.web;

import com.campus.reservation.service.SlotRecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint exposing the Smart Slot & Substitute Recommender.
 */
@Tag(name = "Reservation Recommendations", description = "Sweep-line free slot finder and substitute ranking")
@RestController
@RequestMapping("/api/v1/reservations/recommendations")
@RequiredArgsConstructor
public class SlotRecommendationController {

    private final SlotRecommendationService recommendationService;

    @Operation(
            summary = "Recommend alternative slots and substitute assets",
            description = "Computes the closest free time windows on the requested asset via sweep-line subtraction "
                    + "and ranks free substitute assets for the exact desired slot")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Recommendations computed"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "Asset not found"),
        @ApiResponse(responseCode = "409", description = "Invalid time range or duration")
    })
    @GetMapping
    public ResponseEntity<SlotRecommendationResponse> recommend(
            @RequestParam Long assetId,
            @RequestParam LocalDateTime desiredStart,
            @RequestParam LocalDateTime desiredEnd) {
        return ResponseEntity.ok(recommendationService.recommend(assetId, desiredStart, desiredEnd));
    }
}

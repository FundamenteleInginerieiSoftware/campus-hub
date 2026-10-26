package com.campus.reservation.web;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response returned by the Smart Slot & Substitute Recommender containing sweep-line alternative
 * time slots on the requested asset and scored substitute assets for the exact requested time.
 */
public record SlotRecommendationResponse(
        Long requestedAssetId,
        String requestedAssetName,
        LocalDateTime desiredStart,
        LocalDateTime desiredEnd,
        boolean exactSlotAvailable,
        List<TimeWindowSuggestion> alternativeSlotsOnSameAsset,
        List<SubstituteAssetSuggestion> availableSubstitutesForExactSlot) {

    public record TimeWindowSuggestion(LocalDateTime startTime, LocalDateTime endTime, long shiftMinutesFromDesired) {}

    public record SubstituteAssetSuggestion(
            Long assetId,
            String assetName,
            String model,
            Long labRoomId,
            boolean sameModel,
            boolean sameLabRoom,
            int compatibilityScore) {}
}

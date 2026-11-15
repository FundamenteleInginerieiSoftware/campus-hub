package com.campus.reservation.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class AvailabilityResponseTest {

    private final LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
    private final LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

    @Test
    void aCategoryWithFreeAssetsIsAvailable() {
        AvailabilityResponse response = AvailabilityResponse.forCategory(5L, start, end, List.of(10L, 11L));

        assertThat(response.available()).isTrue();
        assertThat(response.availableAssetIds()).containsExactly(10L, 11L);
        assertThat(response.conflictReason()).isNull();
    }

    @Test
    void aCategoryWithoutFreeAssetsSaysNoAssetsAvailable() {
        AvailabilityResponse response = AvailabilityResponse.forCategory(5L, start, end, List.of());

        assertThat(response.available()).isFalse();
        assertThat(response.conflictReason()).isEqualTo("NO_ASSETS_AVAILABLE");
    }

    @Test
    void forAssetUnavailableWithSubstitutesPopulatesIds() {
        AvailabilityResponse response =
                AvailabilityResponse.forAsset(10L, start, end, false, "RESERVATION_CONFLICT", List.of(12L, 11L));

        assertThat(response.available())
                .as("unavailable asset check must report available=false; see AvailabilityResponse.forAsset")
                .isFalse();
        assertThat(response.availableAssetIds())
                .as("substitute unit IDs must be preserved in caller order; see AvailabilityResponse.forAsset")
                .containsExactly(12L, 11L);
        assertThat(response.conflictReason()).isEqualTo("RESERVATION_CONFLICT");
    }
}

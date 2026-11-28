package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WaitlistPromotionListenerTest {

    @Mock
    private WaitlistService waitlistService;

    @Test
    @DisplayName("onSlotFreed swallows exceptions from WaitlistService so freeing transactions never fail")
    void onSlotFreedSwallowsExceptions() {
        WaitlistPromotionListener listener = new WaitlistPromotionListener(waitlistService);
        doThrow(new RuntimeException("promotion boom")).when(waitlistService).processPromotionsForAsset(10L);

        assertThatCode(() -> listener.onSlotFreed(new SlotFreedEvent(10L)))
                .as("listener must catch errors so cancellations never fail; see WaitlistPromotionListener.onSlotFreed")
                .doesNotThrowAnyException();
        verify(waitlistService).processPromotionsForAsset(10L);
    }
}

package com.campus.reservation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to {@link SlotFreedEvent} (#59) after the freeing transaction commits.
 * {@link WaitlistService#processPromotionsForAsset} executes each promotion step in its own
 * {@code REQUIRES_NEW} transaction so cancellations never roll back and cross-asset promotions
 * cannot deadlock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class WaitlistPromotionListener {

    private final WaitlistService waitlistService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlotFreed(SlotFreedEvent event) {
        try {
            waitlistService.processPromotionsForAsset(event.assetId());
        } catch (RuntimeException ex) {
            log.warn("Waitlist auto-promotion failed for asset #{}: {}", event.assetId(), ex.getMessage());
        }
    }
}

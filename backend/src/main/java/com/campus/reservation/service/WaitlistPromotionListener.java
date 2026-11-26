package com.campus.reservation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reacts to {@link SlotFreedEvent} (#59) after the freeing transaction commits.
 * {@link WaitlistService#processPromotionsForAsset} executes one waiter step per
 * {@code REQUIRES_NEW} transaction (holding at most one user quota lock at a time); any runtime
 * failure is logged without rolling back the freeing action and is recovered on the next lifecycle
 * sweep.
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

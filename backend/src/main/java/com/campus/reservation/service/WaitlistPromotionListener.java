package com.campus.reservation.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reacts to {@link SlotFreedEvent} (#59) after the freeing transaction commits, opening a fresh
 * {@code REQUIRES_NEW} transaction so {@link WaitlistService#processPromotionsForAsset} acquires
 * locks in the standard {@code asset -> userQuota} order and never rolls back cancellations.
 */
@Slf4j
@Service
class WaitlistPromotionListener {

    private final WaitlistService waitlistService;
    private final TransactionTemplate requiresNewTx;

    WaitlistPromotionListener(WaitlistService waitlistService, PlatformTransactionManager transactionManager) {
        this.waitlistService = waitlistService;
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTx = tt;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSlotFreed(SlotFreedEvent event) {
        try {
            requiresNewTx.executeWithoutResult(status -> waitlistService.processPromotionsForAsset(event.assetId()));
        } catch (RuntimeException ex) {
            log.warn("Waitlist auto-promotion failed for asset #{}: {}", event.assetId(), ex.getMessage());
        }
    }
}

package com.campus.reassignment.service;

import com.campus.catalog.api.AssetRetiredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link AssetRetiredEvent} after the catalog retire transaction commits (#245) and
 * triggers reassignment (or force-cancellation) of upcoming bookings on the retired asset.
 */
@Component
@RequiredArgsConstructor
class LocalAssetRetiredListener {

    private final ReassignmentService reassignmentService;

    // Intentionally has no app.messaging.sqs.enabled condition: only AssetDamagedEvent would go
    // through SQS; administrative asset retirement always stays local to prevent a future double trigger.
    // AFTER_COMMIT guarantees we only observe the RETIRED asset once AssetService.retire commits
    // (never on a rolled-back retire). @Async runs reassignment in a background thread so a failure
    // is logged and never rolls back or fails the admin's retire request.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(AssetRetiredEvent event) {
        reassignmentService.handle(event);
    }
}

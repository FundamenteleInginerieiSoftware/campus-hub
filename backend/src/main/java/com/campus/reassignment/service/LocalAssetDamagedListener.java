package com.campus.reassignment.service;

import com.campus.incident.api.AssetDamagedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "app.messaging.sqs.enabled", havingValue = "false", matchIfMissing = true)
@RequiredArgsConstructor
class LocalAssetDamagedListener {

    private final ReassignmentService reassignmentService;

    // AFTER_COMMIT guarantees that if the incident transaction rolls back, no reassignment occurs.
    // With @Async enabled via AsyncConfig, reassignment runs in a background thread after the student's
    // report request completes, so any reassignment error is logged and never fails the HTTP response.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(AssetDamagedEvent event) {
        reassignmentService.handle(event);
    }
}

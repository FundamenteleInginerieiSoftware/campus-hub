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

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(AssetDamagedEvent event) {
        reassignmentService.handle(event);
    }
}

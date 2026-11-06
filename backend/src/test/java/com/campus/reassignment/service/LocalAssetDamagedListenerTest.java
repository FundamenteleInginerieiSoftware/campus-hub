package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.campus.incident.api.AssetDamagedEvent;
import java.lang.reflect.Method;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class LocalAssetDamagedListenerTest {

    @Mock
    private ReassignmentService reassignmentService;

    private LocalAssetDamagedListener listener;

    @BeforeEach
    void setUp() {
        listener = new LocalAssetDamagedListener(reassignmentService);
    }

    @Test
    @DisplayName("on(event) delegates directly to reassignmentService.handle(event)")
    void onDelegatesToReassignmentService() {
        AssetDamagedEvent event = new AssetDamagedEvent(100L, 10L, 5L, Instant.parse("2026-11-02T10:00:00Z"));

        listener.on(event);

        verify(reassignmentService).handle(event);
    }

    @Test
    @DisplayName("on method is annotated with @Async and @TransactionalEventListener(phase = AFTER_COMMIT)")
    void onMethodHasCorrectAnnotations() throws NoSuchMethodException {
        Method onMethod = LocalAssetDamagedListener.class.getMethod("on", AssetDamagedEvent.class);

        assertThat(onMethod.isAnnotationPresent(Async.class)).isTrue();

        TransactionalEventListener txListener = onMethod.getAnnotation(TransactionalEventListener.class);
        assertThat(txListener).isNotNull();
        assertThat(txListener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}

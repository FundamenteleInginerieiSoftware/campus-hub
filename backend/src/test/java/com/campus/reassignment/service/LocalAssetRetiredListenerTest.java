package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.campus.catalog.api.AssetRetiredEvent;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
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
class LocalAssetRetiredListenerTest {

    @Mock
    private ReassignmentService reassignmentService;

    private LocalAssetRetiredListener listener;

    @BeforeEach
    void setUp() {
        listener = new LocalAssetRetiredListener(reassignmentService);
    }

    @Test
    @DisplayName("on(AssetRetiredEvent) delegates directly to reassignmentService.handle(event)")
    void onDelegatesToReassignmentService() {
        AssetRetiredEvent event = new AssetRetiredEvent(10L, LocalDateTime.of(2026, 11, 19, 12, 0), 1L);

        listener.on(event);

        verify(reassignmentService).handle(event);
    }

    @Test
    @DisplayName("on(AssetRetiredEvent) is annotated with @Async and @TransactionalEventListener(phase = AFTER_COMMIT)")
    void onMethodHasCorrectAnnotations() throws NoSuchMethodException {
        Method onMethod = LocalAssetRetiredListener.class.getMethod("on", AssetRetiredEvent.class);

        assertThat(onMethod.isAnnotationPresent(Async.class))
                .as("retire reassignment must run asynchronously; see LocalAssetRetiredListener.on")
                .isTrue();

        TransactionalEventListener txListener = onMethod.getAnnotation(TransactionalEventListener.class);
        assertThat(txListener)
                .as("retire listener must use @TransactionalEventListener; see LocalAssetRetiredListener.on")
                .isNotNull();
        assertThat(txListener.phase())
                .as("retire listener must fire AFTER_COMMIT so rolled-back retires never reassign bookings; "
                        + "see LocalAssetRetiredListener.on")
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}

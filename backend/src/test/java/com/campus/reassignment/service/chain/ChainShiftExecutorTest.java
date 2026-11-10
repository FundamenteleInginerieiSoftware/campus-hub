package com.campus.reassignment.service.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.common.error.ConflictException;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationApi;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChainShiftExecutorTest {

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private ReassignmentLogRepository reassignmentLogRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private Clock clock;

    private ChainShiftExecutor executor;

    private final LocalDateTime fixedNow = LocalDateTime.of(2026, 11, 10, 8, 0);

    @BeforeEach
    void setUp() {
        when(clock.instant())
                .thenReturn(fixedNow.atZone(ZoneId.of("Europe/Bucharest")).toInstant());
        when(clock.getZone()).thenReturn(ZoneId.of("Europe/Bucharest"));
        executor = new ChainShiftExecutor(reservationApi, reassignmentLogRepository, notificationService, clock);
    }

    @Test
    @DisplayName("Successful execution moves reservations, logs reassignments, and notifies students")
    void executeChainShift_successful() {
        LocalDateTime t10 = LocalDateTime.of(2026, 11, 15, 10, 0);
        LocalDateTime t12 = LocalDateTime.of(2026, 11, 15, 12, 0);

        ChainShiftStep step1 = new ChainShiftStep(101L, 42L, 1L, "Scope A", 2L, "Scope B", t10, t12, "SameModel");
        ChainShiftStep step2 = new ChainShiftStep(102L, 43L, 2L, "Scope B", 3L, "Scope C", t10, t12, "SameModel");
        ChainShiftPlan plan = new ChainShiftPlan(true, List.of(step1, step2), "Feasible path");

        when(reservationApi.moveToAsset(101L, 2L, "CHAIN_SHIFT: SameModel")).thenReturn(true);
        when(reservationApi.moveToAsset(102L, 3L, "CHAIN_SHIFT: SameModel")).thenReturn(true);

        executor.executeChainShift(plan);

        verify(reservationApi).moveToAsset(101L, 2L, "CHAIN_SHIFT: SameModel");
        verify(reservationApi).moveToAsset(102L, 3L, "CHAIN_SHIFT: SameModel");

        ArgumentCaptor<ReassignmentLog> logCaptor = ArgumentCaptor.forClass(ReassignmentLog.class);
        verify(reassignmentLogRepository, times(2)).save(logCaptor.capture());

        List<ReassignmentLog> logs = logCaptor.getAllValues();
        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).getOutcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(logs.get(0).getIncidentId()).isNull(); // administrative chain shift

        verify(notificationService).notifyUser(eq(42L), anyString(), anyString());
        verify(notificationService).notifyUser(eq(43L), anyString(), anyString());
    }

    @Test
    @DisplayName("Step failure throws ConflictException, preventing partial updates and skipping notifications")
    void executeChainShift_stepFailureThrows() {
        LocalDateTime t10 = LocalDateTime.of(2026, 11, 15, 10, 0);
        LocalDateTime t12 = LocalDateTime.of(2026, 11, 15, 12, 0);

        ChainShiftStep step1 = new ChainShiftStep(101L, 42L, 1L, "Scope A", 2L, "Scope B", t10, t12, "SameModel");
        ChainShiftPlan plan = new ChainShiftPlan(true, List.of(step1), "Feasible path");

        when(reservationApi.moveToAsset(101L, 2L, "CHAIN_SHIFT: SameModel")).thenReturn(false);

        assertThatThrownBy(() -> executor.executeChainShift(plan))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("One or more reservations in the chain could not be moved");

        verify(notificationService, never()).notifyUser(anyLong(), anyString(), anyString());
    }
}

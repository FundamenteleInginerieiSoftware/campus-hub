package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationApi;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.api.SubstituteNoLongerFreeException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ReassignmentBookingProcessorTest {

    @Mock
    private ReservationApi reservationApi;

    @Mock
    private ReassignmentLogRepository reassignmentLogRepository;

    @Mock
    private SubstituteStrategy strategy1;

    @Mock
    private SubstituteStrategy strategy2;

    @Mock
    private PlatformTransactionManager transactionManager;

    private Clock clock;
    private ReassignmentBookingProcessor processor;

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 2, 10, 0);
    private final AssetDamagedEvent event =
            new AssetDamagedEvent(100L, 10L, 42L, Instant.parse("2026-11-02T10:00:00Z"));
    private final AssetSummary damagedAsset =
            new AssetSummary(10L, "Damaged Scope", "DS1054Z", 1L, 101L, AssetStatus.UNDER_MAINTENANCE);
    private final ReservationSummary booking = new ReservationSummary(
            50L,
            1L,
            10L,
            LocalDateTime.of(2026, 11, 3, 14, 0),
            LocalDateTime.of(2026, 11, 3, 16, 0),
            ReservationStatus.CONFIRMED);

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneId.of("Europe/Bucharest"));
        TransactionStatus txStatus = mock(TransactionStatus.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(txStatus);

        processor = new ReassignmentBookingProcessor(
                reservationApi, reassignmentLogRepository, List.of(strategy1, strategy2), clock, transactionManager);
    }

    @Test
    @DisplayName("constructor configures TransactionTemplate with PROPAGATION_REQUIRES_NEW")
    void constructorConfiguresRequiresNewTransaction() {
        TransactionTemplate tt = (TransactionTemplate) ReflectionTestUtils.getField(processor, "requiresNewTx");
        assertThat(tt).isNotNull();
        assertThat(tt.getPropagationBehavior())
                .as("ReassignmentBookingProcessor must isolate each move attempt in REQUIRES_NEW")
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("processSingleBooking successfully reassigns reservation when substitute is available")
    void processSingleBookingSuccessfulReassignment() {
        AssetSummary substitute = new AssetSummary(20L, "Substitute Scope", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);
        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(substitute));
        when(strategy1.name()).thenReturn("SameModelStrategy");
        when(reservationApi.moveToAsset(50L, 10L, 20L, "Incident #100")).thenReturn(Optional.of(1L));

        Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> resultOpt =
                processor.processSingleBooking(event, booking, damagedAsset);

        assertThat(resultOpt).isPresent();
        ReassignmentBookingProcessor.ReassignmentExecutionResult result = resultOpt.get();
        assertThat(result.outcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(result.substituteAssetName()).isEqualTo("Substitute Scope");
        assertThat(result.ownerUserId())
                .as(
                        "result must carry locked ownerUserId returned by moveToAsset; see ReassignmentBookingProcessor.processSingleBooking")
                .isEqualTo(1L);

        verify(reassignmentLogRepository).save(any(ReassignmentLog.class));
        verify(reservationApi, never()).forceCancel(any(), any(), any());
    }

    @Test
    @DisplayName("processSingleBooking skips booking cleanly if moveToAsset returns empty (e.g. no longer CONFIRMED)")
    void processSingleBookingSkipsWhenNoLongerConfirmed() {
        AssetSummary substitute = new AssetSummary(20L, "Substitute Scope", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);
        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(substitute));
        when(reservationApi.moveToAsset(50L, 10L, 20L, "Incident #100")).thenReturn(Optional.empty());

        Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> resultOpt =
                processor.processSingleBooking(event, booking, damagedAsset);

        assertThat(resultOpt).isEmpty();
        verify(reassignmentLogRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "processSingleBooking retries next candidate when first candidate throws SubstituteNoLongerFreeException")
    void processSingleBookingRetriesNextCandidateOnRace() {
        AssetSummary racedCandidate = new AssetSummary(20L, "Raced Scope", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);
        AssetSummary secondCandidate =
                new AssetSummary(30L, "Second Scope", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);

        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(racedCandidate, secondCandidate));
        when(strategy1.name()).thenReturn("SameModelStrategy");
        when(reservationApi.moveToAsset(50L, 10L, 20L, "Incident #100"))
                .thenThrow(new SubstituteNoLongerFreeException("Asset 20 busy"));
        when(reservationApi.moveToAsset(50L, 10L, 30L, "Incident #100")).thenReturn(Optional.of(1L));

        Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> resultOpt =
                processor.processSingleBooking(event, booking, damagedAsset);

        assertThat(resultOpt).isPresent();
        assertThat(resultOpt.get().outcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(resultOpt.get().substituteAssetName()).isEqualTo("Second Scope");
        verify(reassignmentLogRepository).save(any(ReassignmentLog.class));
    }

    @Test
    @DisplayName("processSingleBooking skips candidate already attempted in previous strategy")
    void processSingleBookingDeduplicatesCandidatesAcrossStrategies() {
        AssetSummary candidate = new AssetSummary(20L, "Same Candidate", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);

        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(candidate));
        when(strategy2.findCandidates(booking, damagedAsset)).thenReturn(List.of(candidate));
        when(reservationApi.moveToAsset(50L, 10L, 20L, "Incident #100"))
                .thenThrow(new SubstituteNoLongerFreeException("Asset 20 busy"));
        when(reservationApi.forceCancel(eq(50L), eq(10L), any())).thenReturn(Optional.of(1L));

        Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> resultOpt =
                processor.processSingleBooking(event, booking, damagedAsset);

        assertThat(resultOpt).isPresent();
        assertThat(resultOpt.get().outcome()).isEqualTo(ReassignmentOutcome.FORCE_CANCELLED);
        // moveToAsset must only be called ONCE for asset 20L, because strategy 2 skips it
        verify(reservationApi).moveToAsset(50L, 10L, 20L, "Incident #100");
    }

    @Test
    @DisplayName("processSingleBooking force-cancels when no candidate across all strategies is free")
    void processSingleBookingForceCancelsWhenNoSubstitute() {
        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of());
        when(strategy2.findCandidates(booking, damagedAsset)).thenReturn(List.of());
        when(reservationApi.forceCancel(50L, 10L, "Equipment failure, incident #100"))
                .thenReturn(Optional.of(1L));

        Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> resultOpt =
                processor.processSingleBooking(event, booking, damagedAsset);

        assertThat(resultOpt).isPresent();
        assertThat(resultOpt.get().outcome()).isEqualTo(ReassignmentOutcome.FORCE_CANCELLED);
        assertThat(resultOpt.get().substituteAssetName()).isNull();
        assertThat(resultOpt.get().ownerUserId())
                .as(
                        "force-cancel result must carry locked ownerUserId returned by forceCancel; see ReassignmentBookingProcessor.processSingleBooking")
                .isEqualTo(1L);
        verify(reassignmentLogRepository).save(any(ReassignmentLog.class));
    }

    @Test
    @DisplayName("processSingleBooking returns empty if forceCancel returns empty (already cancelled)")
    void processSingleBookingReturnsEmptyWhenForceCancelReturnsFalse() {
        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of());
        when(strategy2.findCandidates(booking, damagedAsset)).thenReturn(List.of());
        when(reservationApi.forceCancel(50L, 10L, "Equipment failure, incident #100"))
                .thenReturn(Optional.empty());

        Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> resultOpt =
                processor.processSingleBooking(event, booking, damagedAsset);

        assertThat(resultOpt).isEmpty();
        verify(reassignmentLogRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "processSingleBooking retries the same candidate up to MAX_TRANSIENT_CONCURRENCY_RETRIES on transient lock errors (#362 §2.2)")
    void processSingleBookingRetriesSameCandidateOnTransientConcurrencyFailure() {
        AssetSummary candidate = new AssetSummary(20L, "Preferred Scope", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);
        AssetSummary fallback = new AssetSummary(30L, "Fallback Scope", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);

        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(candidate, fallback));
        when(strategy1.name()).thenReturn("SameModelStrategy");
        when(reservationApi.moveToAsset(50L, 10L, 20L, "Incident #100"))
                .thenThrow(new CannotAcquireLockException("Transient lock error 1"))
                .thenThrow(new RuntimeException("deadlock", new SQLException("deadlock", "40P01")))
                .thenReturn(Optional.of(1L));

        Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> resultOpt =
                processor.processSingleBooking(event, booking, damagedAsset);

        assertThat(resultOpt)
                .as(
                        "transient lock failure must retry the same candidate and succeed; see ReassignmentBookingProcessor.processSingleBooking")
                .isPresent();
        assertThat(resultOpt.get().outcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(resultOpt.get().substituteAssetName()).isEqualTo("Preferred Scope");
        verify(reservationApi, times(3)).moveToAsset(50L, 10L, 20L, "Incident #100");
        verify(reservationApi, never()).moveToAsset(50L, 10L, 30L, "Incident #100");
        verify(reservationApi, never()).forceCancel(any(), any(), any());
        verify(reassignmentLogRepository).save(any(ReassignmentLog.class));
    }

    @Test
    @DisplayName(
            "processSingleBooking rethrows after exhausting transient concurrency retries without force-cancelling (#362 §2.2)")
    void processSingleBookingRethrowsAfterExhaustingTransientConcurrencyRetriesWithoutForceCancelling() {
        AssetSummary onlyCandidate = new AssetSummary(20L, "Only Scope", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);

        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(onlyCandidate));
        when(reservationApi.moveToAsset(50L, 10L, 20L, "Incident #100"))
                .thenThrow(new CannotAcquireLockException("Persistent lock contention"));

        assertThatThrownBy(() -> processor.processSingleBooking(event, booking, damagedAsset))
                .as(
                        "exhausted transient concurrency retries must rethrow instead of force-cancelling a free substitute; "
                                + "see ReassignmentBookingProcessor.processSingleBooking")
                .isInstanceOf(CannotAcquireLockException.class);

        verify(reservationApi, times(ReassignmentBookingProcessor.MAX_TRANSIENT_CONCURRENCY_RETRIES + 1))
                .moveToAsset(50L, 10L, 20L, "Incident #100");
        verify(reservationApi, never()).forceCancel(any(), any(), any());
        verify(reassignmentLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("processSingleBooking rethrows unexpected RuntimeException without force-cancelling booking")
    void processSingleBookingRethrowsUnexpectedRuntimeExceptionWithoutForceCancelling() {
        AssetSummary candidate = new AssetSummary(20L, "Faulty Scope", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);

        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(candidate));
        when(reservationApi.moveToAsset(50L, 10L, 20L, "Incident #100"))
                .thenThrow(new IllegalStateException("Unexpected internal failure"));

        assertThatThrownBy(() -> processor.processSingleBooking(event, booking, damagedAsset))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unexpected internal failure");

        // Crucial (#270 review 3): forceCancel must NEVER be called when an unexpected exception occurs
        verify(reservationApi, never()).forceCancel(any(), any(), any());
        verify(reassignmentLogRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "processSingleBooking retries same candidate for wrapped lock/SQLState causes and advances on wrapped SubstituteNoLongerFreeException (#362 §2.2)")
    void processSingleBookingRetriesOnWrappedConcurrencyCauses() {
        AssetSummary wrappedTaken = new AssetSummary(21L, "Scope Taken", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);
        AssetSummary transientWinner =
                new AssetSummary(30L, "Scope Winner", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);

        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(wrappedTaken, transientWinner));
        when(strategy1.name()).thenReturn("SameModelStrategy");
        when(reservationApi.moveToAsset(50L, 10L, 21L, "Incident #100"))
                .thenThrow(new RuntimeException("wrap", new SubstituteNoLongerFreeException("inner taken")));
        when(reservationApi.moveToAsset(50L, 10L, 30L, "Incident #100"))
                .thenThrow(new RuntimeException("lock_not_available", new SQLException("lock", "55P03")))
                .thenThrow(new RuntimeException("serialization", new SQLException("ser", "40001")))
                .thenReturn(Optional.of(1L));

        Optional<ReassignmentBookingProcessor.ReassignmentExecutionResult> result =
                processor.processSingleBooking(event, booking, damagedAsset);

        assertThat(result)
                .as(
                        "wrapped SubstituteNoLongerFreeException must advance to next candidate and wrapped SQLState 55P03/40001 must retry; "
                                + "see ReassignmentBookingProcessor.isTransientConcurrencyError")
                .isPresent();
        assertThat(result.get().substituteAssetName())
                .as("winner candidate must be selected after retrying transient SQLState errors; "
                        + "see ReassignmentBookingProcessor.processSingleBooking")
                .isEqualTo("Scope Winner");
        verify(reservationApi, times(1)).moveToAsset(50L, 10L, 21L, "Incident #100");
        verify(reservationApi, times(3)).moveToAsset(50L, 10L, 30L, "Incident #100");
    }

    @Test
    @DisplayName("processSingleBooking rethrows non-concurrency SQLException without force-cancelling")
    void processSingleBookingRethrowsNonConcurrencySqlException() {
        AssetSummary candidate = new AssetSummary(20L, "Scope 1", "DS1054Z", 1L, 101L, AssetStatus.AVAILABLE);
        when(strategy1.findCandidates(booking, damagedAsset)).thenReturn(List.of(candidate));
        when(reservationApi.moveToAsset(50L, 10L, 20L, "Incident #100"))
                .thenThrow(new RuntimeException("constraint", new SQLException("unique", "23505")));

        assertThatThrownBy(() -> processor.processSingleBooking(event, booking, damagedAsset))
                .as("non-concurrency SQLState (23505) must be rethrown, not treated as a concurrency retry; "
                        + "see ReassignmentBookingProcessor.isTransientConcurrencyError")
                .isInstanceOf(RuntimeException.class);
        verify(reservationApi, never()).forceCancel(any(), any(), any());
    }
}

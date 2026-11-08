package com.campus.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.common.error.InvalidStateTransitionException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationTransferTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 11, 2, 11, 0);

    @Test
    @DisplayName("Constructor: initializes in PENDING status and validates nulls")
    void constructorInitializesPending() {
        ReservationTransfer transfer = new ReservationTransfer(100L, 1L, 2L, NOW);

        assertThat(transfer.getReservationId()).isEqualTo(100L);
        assertThat(transfer.getFromUserId()).isEqualTo(1L);
        assertThat(transfer.getToUserId()).isEqualTo(2L);
        assertThat(transfer.getRequestedAt()).isEqualTo(NOW);
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(transfer.getRespondedAt()).isNull();

        assertThatThrownBy(() -> new ReservationTransfer(null, 1L, 2L, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationTransfer(100L, null, 2L, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationTransfer(100L, 1L, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReservationTransfer(100L, 1L, 2L, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("accept: transitions from PENDING to ACCEPTED and sets respondedAt")
    void accept_success() {
        ReservationTransfer transfer = new ReservationTransfer(100L, 1L, 2L, NOW);
        transfer.accept(LATER);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.ACCEPTED);
        assertThat(transfer.getRespondedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("accept: throws InvalidStateTransitionException if status is not PENDING")
    void accept_throwsIfNotPending() {
        ReservationTransfer transfer = new ReservationTransfer(100L, 1L, 2L, NOW);
        transfer.accept(LATER);

        assertThatThrownBy(() -> transfer.accept(LATER.plusMinutes(5)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot transition transfer in status ACCEPTED; expected PENDING");
    }

    @Test
    @DisplayName("reject: transitions from PENDING to REJECTED and sets respondedAt")
    void reject_success() {
        ReservationTransfer transfer = new ReservationTransfer(100L, 1L, 2L, NOW);
        transfer.reject(LATER);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.REJECTED);
        assertThat(transfer.getRespondedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("reject: throws InvalidStateTransitionException if status is not PENDING")
    void reject_throwsIfNotPending() {
        ReservationTransfer transfer = new ReservationTransfer(100L, 1L, 2L, NOW);
        transfer.reject(LATER);

        assertThatThrownBy(() -> transfer.reject(LATER.plusMinutes(5)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot transition transfer in status REJECTED; expected PENDING");
    }

    @Test
    @DisplayName("cancel: transitions from PENDING to CANCELLED and sets respondedAt")
    void cancel_success() {
        ReservationTransfer transfer = new ReservationTransfer(100L, 1L, 2L, NOW);
        transfer.cancel(LATER);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        assertThat(transfer.getRespondedAt()).isEqualTo(LATER);
    }

    @Test
    @DisplayName("cancel: throws InvalidStateTransitionException if status is not PENDING")
    void cancel_throwsIfNotPending() {
        ReservationTransfer transfer = new ReservationTransfer(100L, 1L, 2L, NOW);
        transfer.cancel(LATER);

        assertThatThrownBy(() -> transfer.cancel(LATER.plusMinutes(5)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot transition transfer in status CANCELLED; expected PENDING");
    }
}

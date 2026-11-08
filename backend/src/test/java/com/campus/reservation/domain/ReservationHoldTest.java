package com.campus.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.common.error.ConflictException;
import com.campus.common.error.InvalidStateTransitionException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationHoldTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 2, 14, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 2, 16, 0);
    private static final LocalDateTime EXPIRES = NOW.plusMinutes(1);

    @Test
    @DisplayName("Hold starts in ACTIVE status with given time boundaries")
    void initialStateIsActive() {
        ReservationHold hold = new ReservationHold(10L, 1L, START, END, EXPIRES, NOW);

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getAssetId()).isEqualTo(10L);
        assertThat(hold.getUserId()).isEqualTo(1L);
        assertThat(hold.getStartTime()).isEqualTo(START);
        assertThat(hold.getEndTime()).isEqualTo(END);
        assertThat(hold.getExpiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    @DisplayName("confirm() succeeds while hold is unexpired, including exact expiry boundary")
    void confirmBeforeExpirySucceeds() {
        ReservationHold hold = new ReservationHold(10L, 1L, START, END, EXPIRES, NOW);

        hold.confirm(NOW.plusSeconds(30));
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);

        // Exact boundary: now == expiresAt
        ReservationHold boundaryHold = new ReservationHold(10L, 1L, START, END, EXPIRES, NOW);
        boundaryHold.confirm(EXPIRES);
        assertThat(boundaryHold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
    }

    @Test
    @DisplayName("confirm() marks hold as EXPIRED and throws HOLD_EXPIRED when TTL has elapsed")
    void confirmAfterExpiryThrowsConflict() {
        ReservationHold hold = new ReservationHold(10L, 1L, START, END, EXPIRES, NOW);

        assertThatThrownBy(() -> hold.confirm(EXPIRES.plusSeconds(1)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("The reservation hold has expired");

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    @DisplayName("confirm() rejects transition if hold is already confirmed or released")
    void confirmNonActiveThrowsInvalidStateTransition() {
        ReservationHold hold = new ReservationHold(10L, 1L, START, END, EXPIRES, NOW);
        hold.release(NOW);

        assertThatThrownBy(() -> hold.confirm(NOW.plusSeconds(10)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot confirm hold in status RELEASED");
    }

    @Test
    @DisplayName("release() transitions ACTIVE hold to RELEASED")
    void releaseActiveSucceeds() {
        ReservationHold hold = new ReservationHold(10L, 1L, START, END, EXPIRES, NOW);

        hold.release(NOW.plusSeconds(10));

        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
    }

    @Test
    @DisplayName("release() rejects transition if hold is already confirmed or released")
    void releaseNonActiveThrowsInvalidStateTransition() {
        ReservationHold hold = new ReservationHold(10L, 1L, START, END, EXPIRES, NOW);
        hold.release(NOW);

        assertThatThrownBy(() -> hold.release(NOW.plusSeconds(10)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Cannot release hold in status RELEASED");
    }

    @Test
    @DisplayName("expire() sets status to EXPIRED only if current time is past expiry")
    void expireTransitionsOnlyWhenDue() {
        ReservationHold hold = new ReservationHold(10L, 1L, START, END, EXPIRES, NOW);

        boolean expiredEarly = hold.expire(NOW.plusSeconds(30));
        assertThat(expiredEarly).isFalse();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        boolean expiredLate = hold.expire(EXPIRES.plusSeconds(5));
        assertThat(expiredLate).isTrue();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
    }
}

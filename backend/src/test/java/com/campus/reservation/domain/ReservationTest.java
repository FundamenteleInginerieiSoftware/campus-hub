package com.campus.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.campus.common.error.InvalidStateTransitionException;
import com.campus.reservation.api.ReservationStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReservationTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 25, 10, 0);
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 25, 14, 0);
    private final LocalDateTime end = LocalDateTime.of(2026, 10, 25, 16, 0);

    private Reservation reservation;

    @BeforeEach
    void setUp() {
        reservation = new Reservation(1L, 10L, start, end, now);
    }

    private Reservation createReservationInStatus(ReservationStatus status) {
        Reservation r = new Reservation(1L, 10L, start, end, now);
        switch (status) {
            case CONFIRMED -> {}
            case CHECKED_OUT -> r.checkOut(now.plusHours(1));
            case RETURNED -> {
                r.checkOut(now.plusHours(1));
                r.returnItem(now.plusHours(2));
            }
            case CANCELLED -> r.cancel(now.plusHours(1), "Cancelled");
            case FORCE_CANCELLED -> r.forceCancel(now.plusHours(1), "Force cancelled");
            case OVERDUE -> {
                r.checkOut(now.plusHours(1));
                r.markOverdue();
            }
            case NO_SHOW -> r.markNoShow();
        }
        return r;
    }

    @Test
    @DisplayName("New reservation is CONFIRMED with safe initial fields")
    void newReservationIsConfirmed() {
        assertEquals(ReservationStatus.CONFIRMED, reservation.getStatus());
        assertEquals(1L, reservation.getUserId());
        assertEquals(10L, reservation.getAssetId());
        assertNull(reservation.getOriginalAssetId());
        assertEquals(start, reservation.getStartTime());
        assertEquals(end, reservation.getEndTime());
        assertEquals(now, reservation.getCreatedAt());
        assertNull(reservation.getCheckedOutAt());
        assertNull(reservation.getReturnedAt());
        assertNull(reservation.getCancelledAt());
        assertNull(reservation.getCancelReason());
    }

    @Test
    @DisplayName("checkOut sets CHECKED_OUT and checkedOutAt from CONFIRMED")
    void checkOutValidFromConfirmed() {
        LocalDateTime checkOutTime = now.plusHours(4);
        reservation.checkOut(checkOutTime);

        assertEquals(ReservationStatus.CHECKED_OUT, reservation.getStatus());
        assertEquals(checkOutTime, reservation.getCheckedOutAt());
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"CHECKED_OUT", "RETURNED", "CANCELLED", "FORCE_CANCELLED", "OVERDUE", "NO_SHOW"})
    @DisplayName("checkOut throws InvalidStateTransitionException from non-CONFIRMED statuses")
    void checkOutInvalidFromOtherStatuses(ReservationStatus initialStatus) {
        Reservation r = createReservationInStatus(initialStatus);

        assertThatThrownBy(() -> r.checkOut(now.plusHours(4)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("returnItem sets RETURNED and returnedAt from CHECKED_OUT")
    void returnItemValidFromCheckedOut() {
        reservation.checkOut(now.plusHours(4));
        LocalDateTime returnTime = now.plusHours(6);

        reservation.returnItem(returnTime);

        assertEquals(ReservationStatus.RETURNED, reservation.getStatus());
        assertEquals(returnTime, reservation.getReturnedAt());
    }

    @Test
    @DisplayName("returnItem sets RETURNED and returnedAt from OVERDUE")
    void returnItemValidFromOverdue() {
        reservation.checkOut(now.plusHours(4));
        reservation.markOverdue();
        LocalDateTime returnTime = now.plusHours(8);

        reservation.returnItem(returnTime);

        assertEquals(ReservationStatus.RETURNED, reservation.getStatus());
        assertEquals(returnTime, reservation.getReturnedAt());
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"CONFIRMED", "RETURNED", "CANCELLED", "FORCE_CANCELLED", "NO_SHOW"})
    @DisplayName("returnItem throws InvalidStateTransitionException when not CHECKED_OUT or OVERDUE")
    void returnItemInvalidFromOtherStatuses(ReservationStatus initialStatus) {
        Reservation r = createReservationInStatus(initialStatus);

        assertThatThrownBy(() -> r.returnItem(now.plusHours(6)))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("cancel sets CANCELLED, cancelledAt, and cancelReason from CONFIRMED")
    void cancelValidFromConfirmed() {
        LocalDateTime cancelTime = now.plusHours(1);
        String reason = "User changed mind";

        reservation.cancel(cancelTime, reason);

        assertEquals(ReservationStatus.CANCELLED, reservation.getStatus());
        assertEquals(cancelTime, reservation.getCancelledAt());
        assertEquals(reason, reservation.getCancelReason());
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"CHECKED_OUT", "RETURNED", "CANCELLED", "FORCE_CANCELLED", "OVERDUE", "NO_SHOW"})
    @DisplayName("cancel throws InvalidStateTransitionException from non-CONFIRMED statuses")
    void cancelInvalidFromOtherStatuses(ReservationStatus initialStatus) {
        Reservation r = createReservationInStatus(initialStatus);

        assertThatThrownBy(() -> r.cancel(now, "Reason"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("forceCancel sets FORCE_CANCELLED, cancelledAt, and cancelReason from CONFIRMED")
    void forceCancelValidFromConfirmed() {
        LocalDateTime cancelTime = now.plusHours(2);
        String reason = "Asset damaged in prior incident";

        reservation.forceCancel(cancelTime, reason);

        assertEquals(ReservationStatus.FORCE_CANCELLED, reservation.getStatus());
        assertEquals(cancelTime, reservation.getCancelledAt());
        assertEquals(reason, reservation.getCancelReason());
    }

    @Test
    @DisplayName("forceCancel is idempotent when reservation is already FORCE_CANCELLED")
    void forceCancelIsIdempotent() {
        LocalDateTime cancelTime = now.plusHours(2);
        String reason = "Initial force cancel";
        reservation.forceCancel(cancelTime, reason);

        // Second call with different reason/time should return silently without throwing
        reservation.forceCancel(now.plusHours(3), "Second force cancel");

        assertEquals(ReservationStatus.FORCE_CANCELLED, reservation.getStatus());
        assertEquals(cancelTime, reservation.getCancelledAt());
        assertEquals(reason, reservation.getCancelReason());
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"CHECKED_OUT", "RETURNED", "CANCELLED", "OVERDUE", "NO_SHOW"})
    @DisplayName("forceCancel throws InvalidStateTransitionException from non-CONFIRMED statuses")
    void forceCancelInvalidFromOtherStatuses(ReservationStatus initialStatus) {
        Reservation r = createReservationInStatus(initialStatus);

        assertThatThrownBy(() -> r.forceCancel(now, "Reason"))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("moveTo updates assetId and records originalAssetId from CONFIRMED")
    void moveToValidFromConfirmed() {
        Long originalId = reservation.getAssetId();
        Long newAssetId = 20L;

        reservation.moveTo(newAssetId);

        assertEquals(newAssetId, reservation.getAssetId());
        assertEquals(originalId, reservation.getOriginalAssetId());
    }

    @Test
    @DisplayName("moveTo preserves originalAssetId when moved multiple times")
    void moveToPreservesOriginalAssetIdOnMultipleMoves() {
        Long originalId = reservation.getAssetId();

        reservation.moveTo(20L);
        reservation.moveTo(30L);

        assertEquals(30L, reservation.getAssetId());
        assertEquals(originalId, reservation.getOriginalAssetId());
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"CHECKED_OUT", "RETURNED", "CANCELLED", "FORCE_CANCELLED", "OVERDUE", "NO_SHOW"})
    @DisplayName("moveTo throws InvalidStateTransitionException from non-CONFIRMED statuses")
    void moveToInvalidFromOtherStatuses(ReservationStatus initialStatus) {
        Reservation r = createReservationInStatus(initialStatus);

        assertThatThrownBy(() -> r.moveTo(99L))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATUS_TRANSITION");
    }

    @Test
    @DisplayName("markNoShow sets NO_SHOW status from CONFIRMED")
    void markNoShowValidFromConfirmed() {
        reservation.markNoShow();
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.NO_SHOW);
    }
}

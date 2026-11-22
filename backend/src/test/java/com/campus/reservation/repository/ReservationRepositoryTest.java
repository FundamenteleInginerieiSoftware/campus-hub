package com.campus.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
class ReservationRepositoryTest {

    private static final Long USER_ID = 100L;
    private static final Long ASSET_ID = 100L;
    private static final Clock TEST_CLOCK =
            Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Reservation existingReservation;

    @BeforeEach
    void setUp() {
        // Seed foreign keys required by reservations schema
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (100, 'student100@campus.test', '{bcrypt}hash', 'Student 100', 'STUDENT', true, NOW())
            ON CONFLICT (id) DO NOTHING
            """);
        jdbcTemplate.update("""
            INSERT INTO categories (id, name)
            VALUES (100, 'Test Category')
            ON CONFLICT (id) DO NOTHING
            """);
        jdbcTemplate.update("""
            INSERT INTO lab_rooms (id, code, name)
            VALUES (100, 'LAB-100', 'Electronics Lab')
            ON CONFLICT (id) DO NOTHING
            """);
        jdbcTemplate.update("""
            INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (100, 'Rigol DS1054Z', 'DS1054Z', 'SN-REPO-100', 'AVAILABLE', 100, 100, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """);

        // Base booking: 14:00 - 16:00
        Reservation base = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0));
        existingReservation = reservationRepository.saveAndFlush(base);
    }

    // 1. Completely before (no touch): 10:00 - 12:00
    @Test
    @DisplayName("Case 1: completely before (10:00 - 12:00) does not overlap")
    void case1_completelyBeforeDoesNotOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 10, 0), LocalDateTime.of(2026, 11, 2, 12, 0), -1L);
        assertThat(overlap).isFalse();
    }

    // 2. Abutting before (back-to-back): 12:00 - 14:00
    @Test
    @DisplayName("Case 2: abutting before / back-to-back (12:00 - 14:00) does not overlap")
    void case2_abuttingBeforeDoesNotOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 12, 0), LocalDateTime.of(2026, 11, 2, 14, 0), -1L);
        assertThat(overlap).isFalse();
    }

    // 3. Overlapping start: 13:00 - 15:00
    @Test
    @DisplayName("Case 3: overlapping start (13:00 - 15:00) overlaps")
    void case3_overlappingStartOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 13, 0), LocalDateTime.of(2026, 11, 2, 15, 0), -1L);
        assertThat(overlap).isTrue();
    }

    // 4. Starts before, ends at existing end: 13:00 - 16:00
    @Test
    @DisplayName("Case 4: starts before, ends at existing end (13:00 - 16:00) overlaps")
    void case4_startsBeforeEndsAtExistingEndOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 13, 0), LocalDateTime.of(2026, 11, 2, 16, 0), -1L);
        assertThat(overlap).isTrue();
    }

    // 5. Exact match: 14:00 - 16:00
    @Test
    @DisplayName("Case 5: exact match (14:00 - 16:00) overlaps")
    void case5_exactMatchOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 14, 0), LocalDateTime.of(2026, 11, 2, 16, 0), -1L);
        assertThat(overlap).isTrue();
    }

    // 6. Enclosed / inside existing: 14:30 - 15:30
    @Test
    @DisplayName("Case 6: enclosed inside existing (14:30 - 15:30) overlaps")
    void case6_enclosedInsideOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 14, 30), LocalDateTime.of(2026, 11, 2, 15, 30), -1L);
        assertThat(overlap).isTrue();
    }

    // 7. Starts at existing start, ends after: 14:00 - 17:00
    @Test
    @DisplayName("Case 7: starts at existing start, ends after (14:00 - 17:00) overlaps")
    void case7_startsAtExistingStartEndsAfterOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 14, 0), LocalDateTime.of(2026, 11, 2, 17, 0), -1L);
        assertThat(overlap).isTrue();
    }

    // 8. Enclosing existing (starts before, ends after): 13:00 - 17:00
    @Test
    @DisplayName("Case 8: enclosing existing (13:00 - 17:00) overlaps")
    void case8_enclosingExistingOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 13, 0), LocalDateTime.of(2026, 11, 2, 17, 0), -1L);
        assertThat(overlap).isTrue();
    }

    // 9. Abutting after (back-to-back): 16:00 - 18:00
    @Test
    @DisplayName("Case 9: abutting after / back-to-back (16:00 - 18:00) does not overlap")
    void case9_abuttingAfterDoesNotOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 16, 0), LocalDateTime.of(2026, 11, 2, 18, 0), -1L);
        assertThat(overlap).isFalse();
    }

    // 10. Completely after (no touch): 17:00 - 19:00
    @Test
    @DisplayName("Case 10: completely after (17:00 - 19:00) does not overlap")
    void case10_completelyAfterDoesNotOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 17, 0), LocalDateTime.of(2026, 11, 2, 19, 0), -1L);
        assertThat(overlap).isFalse();
    }

    @Test
    @DisplayName("Overlapping slot ignored when passing existing reservation ID")
    void ignoreReservationIdBypassesOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                existingReservation.getId());
        assertThat(overlap).isFalse();
    }

    @Test
    @DisplayName("Active count accurately counts user's reservations in active statuses")
    void countsActiveReservations() {
        long count = reservationRepository.countByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("findConfirmedBeforeCutoff uses strict < cutoff so exact +30m boundary is not marked no-show")
    void findConfirmedBeforeCutoffExcludesExactCutoff() {
        // existingReservation has startTime 14:00 (when now = 14:30, cutoff = 14:00)
        assertThat(reservationRepository.findConfirmedBeforeCutoff(LocalDateTime.of(2026, 11, 2, 14, 0)))
                .isEmpty();
        assertThat(reservationRepository.findConfirmedBeforeCutoff(LocalDateTime.of(2026, 11, 2, 14, 1)))
                .extracting(Reservation::getId)
                .containsExactly(existingReservation.getId());
    }

    @Test
    @DisplayName(
            "findFirstByAssetIdAndStatusInOrderByStartTimeDesc returns overdue holder when subsequent reservation is confirmed")
    void findFirstByAssetIdAndStatusInReturnsOverdueHolder() {
        // r1: earlier reservation that was checked out and became OVERDUE
        Reservation r1 = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        r1.checkOut(LocalDateTime.of(2026, 11, 2, 10, 0));
        r1.markOverdue();
        Reservation savedR1 = reservationRepository.saveAndFlush(r1);

        // existingReservation from setUp is CONFIRMED at 14:00 - 16:00
        var holder = reservationRepository.findFirstByAssetIdAndStatusInOrderByStartTimeDesc(
                ASSET_ID, EnumSet.of(ReservationStatus.CHECKED_OUT, ReservationStatus.OVERDUE));

        assertThat(holder).isPresent();
        assertThat(holder.get().getId()).isEqualTo(savedR1.getId());
        assertThat(holder.get().getStatus()).isEqualTo(ReservationStatus.OVERDUE);
    }

    @Test
    @DisplayName("existsOverlap: OVERDUE reservation acts open-ended and blocks subsequent slot until returned")
    void existsOverlapWithOverdueReservationTreatsIntervalAsOpenEnded() {
        // Overdue reservation: 10:00 - 12:00 on ASSET_ID
        // Note: setUp() already creates existingReservation at 14:00 - 16:00.
        // We test a slot at 16:30 - 18:00 (after existingReservation ends) so only the OVERDUE
        // reservation can trigger the conflict.
        Reservation overdue = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        overdue.checkOut(LocalDateTime.of(2026, 11, 2, 10, 0));
        overdue.markOverdue();
        Reservation savedOverdue = reservationRepository.saveAndFlush(overdue);

        // Subsequent slot: 16:30 - 18:00 (past 12:00 overdue endTime and after 14:00-16:00 existingReservation)
        LocalDateTime slotStart = LocalDateTime.of(2026, 11, 2, 16, 30);
        LocalDateTime slotEnd = LocalDateTime.of(2026, 11, 2, 18, 0);

        boolean overlapSubsequent = reservationRepository.existsOverlap(ASSET_ID, slotStart, slotEnd, -1L);
        assertThat(overlapSubsequent)
                .as("Overdue equipment must block upcoming slot even if slotStart is after original endTime")
                .isTrue();

        // Slot completely before the overdue booking started (08:00 - 09:30) does not overlap
        boolean overlapPrior = reservationRepository.existsOverlap(
                ASSET_ID, LocalDateTime.of(2026, 11, 2, 8, 0), LocalDateTime.of(2026, 11, 2, 9, 30), -1L);
        assertThat(overlapPrior).isFalse();

        // Once returned, the overdue reservation no longer blocks future slots
        savedOverdue.returnItem(LocalDateTime.of(2026, 11, 2, 13, 25));
        reservationRepository.saveAndFlush(savedOverdue);

        // Same slot 16:30 - 18:00 now returns false because the overdue equipment was returned
        boolean overlapAfterReturn = reservationRepository.existsOverlap(ASSET_ID, slotStart, slotEnd, -1L);
        assertThat(overlapAfterReturn)
                .as("Same slot must become free immediately after overdue equipment is returned")
                .isFalse();
    }

    @Test
    @DisplayName("findBusyAssetIds: includes asset with OVERDUE reservation for subsequent slot until returned")
    void findBusyAssetIdsIncludesOverdueAsset() {
        Reservation overdue = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 2, 8, 0));
        overdue.checkOut(LocalDateTime.of(2026, 11, 2, 10, 0));
        overdue.markOverdue();
        Reservation savedOverdue = reservationRepository.saveAndFlush(overdue);

        LocalDateTime slotStart = LocalDateTime.of(2026, 11, 2, 16, 30);
        LocalDateTime slotEnd = LocalDateTime.of(2026, 11, 2, 18, 0);

        List<Long> busyIds = reservationRepository.findBusyAssetIds(slotStart, slotEnd);
        assertThat(busyIds).contains(ASSET_ID);

        savedOverdue.returnItem(LocalDateTime.of(2026, 11, 2, 13, 25));
        reservationRepository.saveAndFlush(savedOverdue);

        List<Long> busyIdsAfterReturn = reservationRepository.findBusyAssetIds(slotStart, slotEnd);
        assertThat(busyIdsAfterReturn).doesNotContain(ASSET_ID);
    }

    @Test
    @DisplayName("existsOverlap & findBusyAssetIds: ignore terminal statuses (CANCELLED, NO_SHOW, FORCE_CANCELLED)")
    void overlapAndBusyQueriesIgnoreTerminalStatuses() {
        LocalDateTime slotStart = LocalDateTime.of(2026, 11, 3, 10, 0);
        LocalDateTime slotEnd = LocalDateTime.of(2026, 11, 3, 12, 0);
        LocalDateTime created = LocalDateTime.of(2026, 11, 2, 8, 0);

        Reservation cancelled = new Reservation(USER_ID, ASSET_ID, slotStart, slotEnd, created);
        cancelled.cancel(created.plusHours(1), "Cancelled");
        reservationRepository.saveAndFlush(cancelled);

        Reservation noShow = new Reservation(USER_ID, ASSET_ID, slotStart, slotEnd, created);
        noShow.markNoShow();
        reservationRepository.saveAndFlush(noShow);

        Reservation forceCancelled = new Reservation(USER_ID, ASSET_ID, slotStart, slotEnd, created);
        forceCancelled.forceCancel(created.plusHours(1), "Asset damaged");
        reservationRepository.saveAndFlush(forceCancelled);

        assertThat(reservationRepository.existsOverlap(ASSET_ID, slotStart, slotEnd, -1L))
                .as("terminal statuses must not block slots; see ReservationRepository.existsOverlap")
                .isFalse();
        assertThat(reservationRepository.findBusyAssetIds(slotStart, slotEnd))
                .as("terminal statuses must not mark assets busy; see ReservationRepository.findBusyAssetIds")
                .doesNotContain(ASSET_ID);
    }

    @Test
    @DisplayName("findCheckedOutPastEnd and findUpcomingConfirmedByAsset: return ordered rows for lifecycle sweeps")
    void lifecycleSweepQueriesReturnMatchingRows() {
        Reservation checkedOutExpired = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 7, 0));
        checkedOutExpired.checkOut(LocalDateTime.of(2026, 11, 2, 8, 5));
        Reservation savedExpired = reservationRepository.saveAndFlush(checkedOutExpired);

        List<Reservation> expired = reservationRepository.findCheckedOutPastEnd(LocalDateTime.of(2026, 11, 2, 10, 1));
        assertThat(expired).extracting(Reservation::getId).containsExactly(savedExpired.getId());

        // Exact endTime is not yet past end (< :now)
        assertThat(reservationRepository.findCheckedOutPastEnd(LocalDateTime.of(2026, 11, 2, 10, 0)))
                .isEmpty();

        // existingReservation is CONFIRMED 14:00 - 16:00
        List<Reservation> upcoming =
                reservationRepository.findUpcomingConfirmedByAsset(ASSET_ID, LocalDateTime.of(2026, 11, 2, 12, 0));
        assertThat(upcoming).extracting(Reservation::getId).containsExactly(existingReservation.getId());
    }

    @Test
    @DisplayName("countBookingSessionsByUserIdAndStatusIn: treats multi-item bundle as 1 session")
    void countBookingSessions_countsBundleAsOneSession() {
        UUID bundleId = UUID.randomUUID();

        // existingReservation is a single booking (userId = 100) -> 1 session
        long initialCount = reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                USER_ID, ReservationStatus.ACTIVE_STATUSES);
        assertThat(initialCount)
                .as("Initial single booking counts as 1 session")
                .isEqualTo(1);

        // Add 3 reservations belonging to the same bundle
        Reservation item1 = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 3, 10, 0),
                LocalDateTime.of(2026, 11, 3, 12, 0),
                LocalDateTime.of(2026, 11, 2, 9, 0));
        item1.assignBundle(bundleId);
        reservationRepository.saveAndFlush(item1);

        Reservation item2 = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 3, 10, 0),
                LocalDateTime.of(2026, 11, 3, 12, 0),
                LocalDateTime.of(2026, 11, 2, 9, 0));
        item2.assignBundle(bundleId);
        reservationRepository.saveAndFlush(item2);

        Reservation item3 = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 3, 10, 0),
                LocalDateTime.of(2026, 11, 3, 12, 0),
                LocalDateTime.of(2026, 11, 2, 9, 0));
        item3.assignBundle(bundleId);
        reservationRepository.saveAndFlush(item3);

        // Total active rows = 4, but total booking sessions = 2 (1 single + 1 bundle of 3)
        long rowCount = reservationRepository.countByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES);
        long sessionCount = reservationRepository.countBookingSessionsByUserIdAndStatusIn(
                USER_ID, ReservationStatus.ACTIVE_STATUSES);

        assertThat(rowCount)
                .as("Raw reservation row count should be 4 (1 single + 3 in bundle)")
                .isEqualTo(4);
        assertThat(sessionCount)
                .as("Booking session count should be 2 (1 single + 1 bundle of 3)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName(
            "findUpcomingConfirmedByUser: returns confirmed reservations ending after cutoff ordered by start time")
    void findUpcomingConfirmedByUser_returnsMatchingReservations() {
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (101, 'student101@campus.test', '{bcrypt}hash', 'Student 101', 'STUDENT', true, NOW())
            ON CONFLICT (id) DO NOTHING
            """);

        // existingReservation is USER_ID (100), CONFIRMED, 14:00 - 16:00
        Reservation past = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 7, 0));
        reservationRepository.saveAndFlush(past);

        Reservation otherUser = new Reservation(
                101L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 18, 0),
                LocalDateTime.of(2026, 11, 2, 7, 0));
        reservationRepository.saveAndFlush(otherUser);

        Reservation checkedOut = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 18, 0),
                LocalDateTime.of(2026, 11, 2, 19, 0),
                LocalDateTime.of(2026, 11, 2, 7, 0));
        checkedOut.checkOut(LocalDateTime.of(2026, 11, 2, 18, 5));
        reservationRepository.saveAndFlush(checkedOut);

        Reservation overdue = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 3, 8, 0),
                LocalDateTime.of(2026, 11, 3, 10, 0),
                LocalDateTime.of(2026, 11, 2, 7, 0));
        overdue.checkOut(LocalDateTime.of(2026, 11, 3, 8, 5));
        overdue.markOverdue();
        reservationRepository.saveAndFlush(overdue);

        Reservation cancelled = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 3, 10, 0),
                LocalDateTime.of(2026, 11, 3, 12, 0),
                LocalDateTime.of(2026, 11, 2, 7, 0));
        cancelled.cancel(LocalDateTime.of(2026, 11, 2, 11, 0), "Cancelled earlier");
        reservationRepository.saveAndFlush(cancelled);

        List<Reservation> upcoming =
                reservationRepository.findUpcomingConfirmedByUser(USER_ID, LocalDateTime.of(2026, 11, 2, 12, 0));

        assertThat(upcoming)
                .as(
                        "findUpcomingConfirmedByUser must return only CONFIRMED bookings for USER_ID ending after 12:00 (excluding past, other user, CHECKED_OUT, OVERDUE, and CANCELLED)")
                .extracting(Reservation::getId)
                .containsExactly(existingReservation.getId());
        assertThat(upcoming)
                .as("CHECKED_OUT, OVERDUE, and CANCELLED bookings for USER_ID must not be returned")
                .extracting(Reservation::getStatus)
                .containsOnly(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName(
            "findByUserIdOverlappingRange: uses half-open overlap (abutting edges excluded, 1-second overlap included)")
    void findByUserIdOverlappingRange_respectsHalfOpenEdgeBoundaries() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 10, 10, 0, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 20, 10, 0, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 1, 9, 0, 0);

        // endTime == from -> abutting before, excluded by r.endTime > :from
        Reservation abuttingBefore = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 10, 8, 0, 0),
                LocalDateTime.of(2026, 11, 10, 10, 0, 0),
                createdAt);
        reservationRepository.saveAndFlush(abuttingBefore);

        // startTime == to -> abutting after, excluded by r.startTime < :to
        Reservation abuttingAfter = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 20, 10, 0, 0),
                LocalDateTime.of(2026, 11, 20, 12, 0, 0),
                createdAt);
        reservationRepository.saveAndFlush(abuttingAfter);

        // 1-second overlap at start: endTime = from + 1s
        Reservation overlapStart = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 10, 9, 0, 0),
                LocalDateTime.of(2026, 11, 10, 10, 0, 1),
                createdAt);
        Reservation savedOverlapStart = reservationRepository.saveAndFlush(overlapStart);

        // 1-second overlap at end: startTime = to - 1s
        Reservation overlapEnd = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 20, 9, 59, 59),
                LocalDateTime.of(2026, 11, 20, 11, 0, 0),
                createdAt);
        Reservation savedOverlapEnd = reservationRepository.saveAndFlush(overlapEnd);

        List<Reservation> results = reservationRepository.findByUserIdOverlappingRange(USER_ID, from, to);

        assertThat(results)
                .as(
                        "findByUserIdOverlappingRange must exclude abutting [end==from] and [start==to] and include 1-second overlaps; see ReservationRepository.findByUserIdOverlappingRange")
                .extracting(Reservation::getId)
                .containsExactly(savedOverlapStart.getId(), savedOverlapEnd.getId());
    }

    @Test
    @DisplayName("findByUserIdOverlappingRange: returns every status ordered by startTime asc and excludes other users")
    void findByUserIdOverlappingRange_returnsAllStatusesOrderedAndExcludesOtherUsers() {
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (101, 'student101@campus.test', '{bcrypt}hash', 'Student 101', 'STUDENT', true, NOW())
            ON CONFLICT (id) DO NOTHING
            """);

        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 1, 9, 0, 0);

        Reservation otherUser = new Reservation(
                101L,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 12, 12, 0),
                LocalDateTime.of(2026, 11, 12, 14, 0),
                createdAt);
        reservationRepository.saveAndFlush(otherUser);

        Reservation confirmed = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 15, 10, 0),
                LocalDateTime.of(2026, 11, 15, 12, 0),
                createdAt);
        Reservation savedConfirmed = reservationRepository.saveAndFlush(confirmed);

        Reservation checkedOut = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 12, 10, 0),
                LocalDateTime.of(2026, 11, 12, 12, 0),
                createdAt);
        checkedOut.checkOut(LocalDateTime.of(2026, 11, 12, 10, 0));
        Reservation savedCheckedOut = reservationRepository.saveAndFlush(checkedOut);

        Reservation overdue = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 11, 10, 0),
                LocalDateTime.of(2026, 11, 11, 12, 0),
                createdAt);
        overdue.checkOut(LocalDateTime.of(2026, 11, 11, 10, 0));
        overdue.markOverdue();
        Reservation savedOverdue = reservationRepository.saveAndFlush(overdue);

        Reservation returned = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 13, 10, 0),
                LocalDateTime.of(2026, 11, 13, 12, 0),
                createdAt);
        returned.checkOut(LocalDateTime.of(2026, 11, 13, 10, 0));
        returned.returnItem(LocalDateTime.of(2026, 11, 13, 11, 30));
        Reservation savedReturned = reservationRepository.saveAndFlush(returned);

        Reservation cancelled = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 14, 10, 0),
                LocalDateTime.of(2026, 11, 14, 12, 0),
                createdAt);
        cancelled.cancel(LocalDateTime.of(2026, 11, 13, 15, 0), "No longer needed");
        Reservation savedCancelled = reservationRepository.saveAndFlush(cancelled);

        Reservation forceCancelled = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 16, 10, 0),
                LocalDateTime.of(2026, 11, 16, 12, 0),
                createdAt);
        forceCancelled.forceCancel(LocalDateTime.of(2026, 11, 15, 12, 0), "Asset damaged");
        Reservation savedForceCancelled = reservationRepository.saveAndFlush(forceCancelled);

        Reservation noShow = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 17, 10, 0),
                LocalDateTime.of(2026, 11, 17, 12, 0),
                createdAt);
        noShow.markNoShow();
        Reservation savedNoShow = reservationRepository.saveAndFlush(noShow);

        List<Reservation> results = reservationRepository.findByUserIdOverlappingRange(
                USER_ID, LocalDateTime.of(2026, 11, 10, 0, 0), LocalDateTime.of(2026, 11, 20, 0, 0));

        assertThat(results)
                .as(
                        "findByUserIdOverlappingRange must return all statuses for USER_ID ordered by startTime asc and exclude other users; see ReservationRepository.findByUserIdOverlappingRange")
                .extracting(Reservation::getId)
                .containsExactly(
                        savedOverdue.getId(),
                        savedCheckedOut.getId(),
                        savedReturned.getId(),
                        savedCancelled.getId(),
                        savedConfirmed.getId(),
                        savedForceCancelled.getId(),
                        savedNoShow.getId());
    }

    @Test
    @DisplayName(
            "findCheckedOutOverlapping: includes returned overlapping [from, to] edges and active unreturned checkouts")
    void findCheckedOutOverlapping_includesReturnedAndActiveOverlappingWindow() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 2, 10, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 2, 18, 0);

        // 1. Returned completely inside [10:00, 18:00]
        Reservation returnedInside = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        returnedInside.checkOut(LocalDateTime.of(2026, 11, 2, 11, 0));
        returnedInside.returnItem(LocalDateTime.of(2026, 11, 2, 12, 0));
        Reservation savedInside = reservationRepository.saveAndFlush(returnedInside);

        // 2. Returned overlapping the 'from' boundary (08:30 - 10:30)
        Reservation returnedCrossingFrom = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 30),
                LocalDateTime.of(2026, 11, 2, 10, 30),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        returnedCrossingFrom.checkOut(LocalDateTime.of(2026, 11, 2, 8, 30));
        returnedCrossingFrom.returnItem(LocalDateTime.of(2026, 11, 2, 10, 30));
        Reservation savedCrossingFrom = reservationRepository.saveAndFlush(returnedCrossingFrom);

        // 3. Returned overlapping the 'to' boundary (17:00 - 19:00)
        Reservation returnedCrossingTo = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 17, 0),
                LocalDateTime.of(2026, 11, 2, 19, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        returnedCrossingTo.checkOut(LocalDateTime.of(2026, 11, 2, 17, 0));
        returnedCrossingTo.returnItem(LocalDateTime.of(2026, 11, 2, 19, 0));
        Reservation savedCrossingTo = reservationRepository.saveAndFlush(returnedCrossingTo);

        // 4. Active CHECKED_OUT started before 'from' with returnedAt == null
        Reservation activeCheckedOut = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 9, 0),
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        activeCheckedOut.checkOut(LocalDateTime.of(2026, 11, 2, 9, 0));
        Reservation savedCheckedOut = reservationRepository.saveAndFlush(activeCheckedOut);

        // 5. Active OVERDUE inside window with returnedAt == null
        Reservation activeOverdue = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 2, 13, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        activeOverdue.checkOut(LocalDateTime.of(2026, 11, 2, 12, 0));
        activeOverdue.markOverdue();
        Reservation savedOverdue = reservationRepository.saveAndFlush(activeOverdue);

        List<Reservation> result = reservationRepository.findCheckedOutOverlapping(from, to);

        assertThat(result)
                .as(
                        "findCheckedOutOverlapping must include returned bookings overlapping [from, to] and active CHECKED_OUT/OVERDUE rows; see ReservationRepository.findCheckedOutOverlapping")
                .extracting(Reservation::getId)
                .containsExactlyInAnyOrder(
                        savedInside.getId(),
                        savedCrossingFrom.getId(),
                        savedCrossingTo.getId(),
                        savedCheckedOut.getId(),
                        savedOverdue.getId());
    }

    @Test
    @DisplayName("findCheckedOutOverlapping: excludes never-picked-up bookings and checkouts outside [from, to]")
    void findCheckedOutOverlapping_excludesUnpickedUpAndOutsideWindow() {
        LocalDateTime from = LocalDateTime.of(2026, 11, 2, 10, 0);
        LocalDateTime to = LocalDateTime.of(2026, 11, 2, 18, 0);

        // existingReservation from setUp is CONFIRMED inside [14:00, 16:00] with checkedOutAt == null

        Reservation cancelled = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 11, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        cancelled.cancel(LocalDateTime.of(2026, 11, 1, 12, 0), "No longer needed");
        reservationRepository.saveAndFlush(cancelled);

        Reservation noShow = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 2, 13, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        noShow.markNoShow();
        reservationRepository.saveAndFlush(noShow);

        // Returned completely before 'from' (08:00 - 09:30)
        Reservation returnedBefore = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 9, 30),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        returnedBefore.checkOut(LocalDateTime.of(2026, 11, 2, 8, 0));
        returnedBefore.returnItem(LocalDateTime.of(2026, 11, 2, 9, 30));
        reservationRepository.saveAndFlush(returnedBefore);

        // Returned exactly at 'from' (08:00 - 10:00) -> 0 minutes overlap
        Reservation returnedAtFrom = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 8, 0),
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        returnedAtFrom.checkOut(LocalDateTime.of(2026, 11, 2, 8, 0));
        returnedAtFrom.returnItem(from);
        reservationRepository.saveAndFlush(returnedAtFrom);

        // Checked out exactly at 'to' (18:00 - 19:00) -> 0 minutes overlap
        Reservation checkedOutAtTo = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 18, 0),
                LocalDateTime.of(2026, 11, 2, 19, 0),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        checkedOutAtTo.checkOut(to);
        checkedOutAtTo.returnItem(LocalDateTime.of(2026, 11, 2, 19, 0));
        reservationRepository.saveAndFlush(checkedOutAtTo);

        // Active CHECKED_OUT starting after 'to' (18:30)
        Reservation activeAfterTo = new Reservation(
                USER_ID,
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 18, 30),
                LocalDateTime.of(2026, 11, 2, 19, 30),
                LocalDateTime.of(2026, 11, 1, 9, 0));
        activeAfterTo.checkOut(LocalDateTime.of(2026, 11, 2, 18, 30));
        reservationRepository.saveAndFlush(activeAfterTo);

        List<Reservation> result = reservationRepository.findCheckedOutOverlapping(from, to);

        assertThat(result)
                .as(
                        "findCheckedOutOverlapping must exclude CONFIRMED/CANCELLED/NO_SHOW and checkouts outside [from, to]; see ReservationRepository.findCheckedOutOverlapping")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "findByBundleIdForUpdate returns bundle reservations ordered by ascending id even when assetId order differs (#336)")
    void findByBundleIdForUpdate_returnsOrderedByIdAsc() {
        jdbcTemplate.update("""
            INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (101, 'Rigol DS1054Z #2', 'DS1054Z', 'SN-REPO-101', 'AVAILABLE', 100, 100, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """);
        UUID bundleId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(TEST_CLOCK).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = now.plusHours(4);
        LocalDateTime end = start.plusHours(2);
        LocalDateTime created = now.minusDays(1);

        Reservation rHigherAssetLowerId = new Reservation(USER_ID, 101L, start, end, created);
        rHigherAssetLowerId.assignBundle(bundleId);
        rHigherAssetLowerId = reservationRepository.saveAndFlush(rHigherAssetLowerId);

        Reservation rLowerAssetHigherId = new Reservation(USER_ID, ASSET_ID, start, end, created);
        rLowerAssetHigherId.assignBundle(bundleId);
        rLowerAssetHigherId = reservationRepository.saveAndFlush(rLowerAssetHigherId);

        List<Reservation> lockedItems = reservationRepository.findByBundleIdForUpdate(bundleId);

        assertThat(lockedItems)
                .as(
                        "findByBundleIdForUpdate must return all bundle items ordered by ascending id (matching findUpcomingConfirmedByUserForUpdate); "
                                + "see ReservationRepository.findByBundleIdForUpdate")
                .extracting(Reservation::getId)
                .containsExactly(rHigherAssetLowerId.getId(), rLowerAssetHigherId.getId());
    }

    @Test
    @DisplayName(
            "findByBundleIdAndUserIdForUpdate returns only the caller's bundle items ordered by ascending id (#338)")
    void findByBundleIdAndUserIdForUpdate_returnsOnlyOwnedItemsOrderedByIdAsc() {
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (101, 'student101@campus.test', '{bcrypt}hash', 'Student 101', 'STUDENT', true, NOW())
            ON CONFLICT (id) DO NOTHING
            """);
        jdbcTemplate.update("""
            INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (101, 'Rigol DS1054Z #2', 'DS1054Z', 'SN-REPO-101', 'AVAILABLE', 100, 100, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """);
        jdbcTemplate.update("""
            INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (102, 'Rigol DS1054Z #3', 'DS1054Z', 'SN-REPO-102', 'AVAILABLE', 100, 100, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """);
        UUID bundleId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(TEST_CLOCK).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = now.plusHours(4);
        LocalDateTime end = start.plusHours(2);
        LocalDateTime created = now.minusDays(1);

        Reservation r1 = new Reservation(USER_ID, 101L, start, end, created);
        r1.assignBundle(bundleId);
        r1 = reservationRepository.saveAndFlush(r1);

        Reservation rOtherUser = new Reservation(101L, 102L, start, end, created);
        rOtherUser.assignBundle(bundleId);
        reservationRepository.saveAndFlush(rOtherUser);

        Reservation r2 = new Reservation(USER_ID, ASSET_ID, start, end, created);
        r2.assignBundle(bundleId);
        r2 = reservationRepository.saveAndFlush(r2);

        List<Reservation> lockedOwnedItems = reservationRepository.findByBundleIdAndUserIdForUpdate(bundleId, USER_ID);

        assertThat(lockedOwnedItems)
                .as(
                        "findByBundleIdAndUserIdForUpdate must exclude items transferred to other users and order by ascending id; "
                                + "see ReservationRepository.findByBundleIdAndUserIdForUpdate")
                .extracting(Reservation::getId)
                .containsExactly(r1.getId(), r2.getId());
    }
}

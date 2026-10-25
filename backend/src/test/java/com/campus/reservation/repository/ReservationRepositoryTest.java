package com.campus.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import java.time.LocalDateTime;
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
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 10, 0),
                LocalDateTime.of(2026, 11, 2, 12, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isFalse();
    }

    // 2. Abutting before (back-to-back): 12:00 - 14:00
    @Test
    @DisplayName("Case 2: abutting before / back-to-back (12:00 - 14:00) does not overlap")
    void case2_abuttingBeforeDoesNotOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 12, 0),
                LocalDateTime.of(2026, 11, 2, 14, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isFalse();
    }

    // 3. Overlapping start: 13:00 - 15:00
    @Test
    @DisplayName("Case 3: overlapping start (13:00 - 15:00) overlaps")
    void case3_overlappingStartOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 13, 0),
                LocalDateTime.of(2026, 11, 2, 15, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isTrue();
    }

    // 4. Starts before, ends at existing end: 13:00 - 16:00
    @Test
    @DisplayName("Case 4: starts before, ends at existing end (13:00 - 16:00) overlaps")
    void case4_startsBeforeEndsAtExistingEndOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 13, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isTrue();
    }

    // 5. Exact match: 14:00 - 16:00
    @Test
    @DisplayName("Case 5: exact match (14:00 - 16:00) overlaps")
    void case5_exactMatchOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isTrue();
    }

    // 6. Enclosed / inside existing: 14:30 - 15:30
    @Test
    @DisplayName("Case 6: enclosed inside existing (14:30 - 15:30) overlaps")
    void case6_enclosedInsideOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 30),
                LocalDateTime.of(2026, 11, 2, 15, 30),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isTrue();
    }

    // 7. Starts at existing start, ends after: 14:00 - 17:00
    @Test
    @DisplayName("Case 7: starts at existing start, ends after (14:00 - 17:00) overlaps")
    void case7_startsAtExistingStartEndsAfterOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 17, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isTrue();
    }

    // 8. Enclosing existing (starts before, ends after): 13:00 - 17:00
    @Test
    @DisplayName("Case 8: enclosing existing (13:00 - 17:00) overlaps")
    void case8_enclosingExistingOverlaps() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 13, 0),
                LocalDateTime.of(2026, 11, 2, 17, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isTrue();
    }

    // 9. Abutting after (back-to-back): 16:00 - 18:00
    @Test
    @DisplayName("Case 9: abutting after / back-to-back (16:00 - 18:00) does not overlap")
    void case9_abuttingAfterDoesNotOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 16, 0),
                LocalDateTime.of(2026, 11, 2, 18, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isFalse();
    }

    // 10. Completely after (no touch): 17:00 - 19:00
    @Test
    @DisplayName("Case 10: completely after (17:00 - 19:00) does not overlap")
    void case10_completelyAfterDoesNotOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 17, 0),
                LocalDateTime.of(2026, 11, 2, 19, 0),
                ReservationStatus.ACTIVE_STATUSES,
                -1L);
        assertThat(overlap).isFalse();
    }

    @Test
    @DisplayName("Overlapping slot ignored when passing existing reservation ID")
    void ignoreReservationIdBypassesOverlap() {
        boolean overlap = reservationRepository.existsOverlap(
                ASSET_ID,
                LocalDateTime.of(2026, 11, 2, 14, 0),
                LocalDateTime.of(2026, 11, 2, 16, 0),
                ReservationStatus.ACTIVE_STATUSES,
                existingReservation.getId());
        assertThat(overlap).isFalse();
    }

    @Test
    @DisplayName("Active count accurately counts user's reservations in active statuses")
    void countsActiveReservations() {
        long count = reservationRepository.countByUserIdAndStatusIn(USER_ID, ReservationStatus.ACTIVE_STATUSES);
        assertThat(count).isEqualTo(1L);
    }
}

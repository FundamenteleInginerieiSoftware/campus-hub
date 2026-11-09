package com.campus.reservation.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.ReservationHold;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
class ReservationHoldRepositoryTest {

    private static final Long USER_ID = 100L;
    private static final Long ASSET_ID = 100L;

    @Autowired
    private ReservationHoldRepository reservationHoldRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 2, 14, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 2, 16, 0);
    private static final LocalDateTime EXPIRES = NOW.plusMinutes(1);

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (100, 'holduser@campus.test', '{bcrypt}hash', 'Hold User', 'STUDENT', true, NOW())
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
            VALUES (100, 'Rigol DS1054Z', 'DS1054Z', 'SN-HOLD-100', 'AVAILABLE', 100, 100, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """);
    }

    @Test
    @DisplayName("existsActiveOverlap: detects active unexpired hold overlapping with time slot")
    void existsActiveOverlap_detectsOverlap() {
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, EXPIRES, NOW);
        reservationHoldRepository.saveAndFlush(hold);

        // 14:30 - 15:30 overlaps with 14:00 - 16:00
        boolean overlaps = reservationHoldRepository.existsActiveOverlap(
                ASSET_ID, START.plusMinutes(30), END.minusMinutes(30), NOW, null, null);
        assertThat(overlaps).isTrue();

        // 16:00 - 18:00 (abutting after) does not overlap
        boolean afterAbutting =
                reservationHoldRepository.existsActiveOverlap(ASSET_ID, END, END.plusHours(2), NOW, null, null);
        assertThat(afterAbutting).isFalse();

        // Different asset does not overlap
        boolean diffAsset = reservationHoldRepository.existsActiveOverlap(999L, START, END, NOW, null, null);
        assertThat(diffAsset).isFalse();

        // Expired hold does not overlap (query checked at EXPIRES + 1s)
        boolean afterExpired =
                reservationHoldRepository.existsActiveOverlap(ASSET_ID, START, END, EXPIRES.plusSeconds(1), null, null);
        assertThat(afterExpired).isFalse();
    }

    @Test
    @DisplayName("expireOldHolds: batch marks active holds past expiresAt as EXPIRED")
    void expireOldHolds_updatesStatus() {
        // Hold 1: expires at 10:01
        ReservationHold hold1 = new ReservationHold(ASSET_ID, USER_ID, START, END, NOW.plusMinutes(1), NOW);
        // Hold 2: expires at 10:05
        ReservationHold hold2 =
                new ReservationHold(ASSET_ID, USER_ID, START.plusHours(3), END.plusHours(3), NOW.plusMinutes(5), NOW);
        reservationHoldRepository.saveAllAndFlush(List.of(hold1, hold2));

        // Sweep at 10:02 -> hold1 expired, hold2 still active
        int updated = reservationHoldRepository.expireOldHolds(NOW.plusMinutes(2));
        assertThat(updated).isEqualTo(1);

        ReservationHold reloaded1 =
                reservationHoldRepository.findById(hold1.getId()).orElseThrow();
        ReservationHold reloaded2 =
                reservationHoldRepository.findById(hold2.getId()).orElseThrow();

        assertThat(reloaded1.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        assertThat(reloaded2.getStatus()).isEqualTo(HoldStatus.ACTIVE);
    }

    @Test
    @DisplayName(
            "existsRecentHoldOverlap: detects recent hold on overlapping slot within cooldown threshold (#240 point 4)")
    void existsRecentHoldOverlap_detectsOverlappingHoldWithinThreshold() {
        // Hold on 14:00 - 16:00 created at NOW
        ReservationHold hold = new ReservationHold(ASSET_ID, USER_ID, START, END, EXPIRES, NOW);
        reservationHoldRepository.saveAndFlush(hold);

        LocalDateTime threshold = NOW.minusMinutes(2);

        // 1. Exact slot (14:00 - 16:00) overlaps
        assertThat(reservationHoldRepository.existsRecentHoldOverlap(USER_ID, ASSET_ID, START, END, threshold))
                .as("cooldown: exact slot must match recent hold")
                .isTrue();

        // 2. Sub-slot (14:00 - 15:30) overlaps — prevents script variation loop (#240 review)
        assertThat(reservationHoldRepository.existsRecentHoldOverlap(
                        USER_ID, ASSET_ID, START, START.plusMinutes(90), threshold))
                .as("cooldown: sub-slot overlapping 14:00-16:00 must be blocked by cooldown")
                .isTrue();

        // 3. Partial overlap (15:00 - 17:00) overlaps
        assertThat(reservationHoldRepository.existsRecentHoldOverlap(
                        USER_ID, ASSET_ID, START.plusHours(1), END.plusHours(1), threshold))
                .as("cooldown: partially overlapping window must be blocked")
                .isTrue();

        // 4. Non-overlapping abutting slot (16:00 - 18:00) does NOT overlap
        assertThat(reservationHoldRepository.existsRecentHoldOverlap(
                        USER_ID, ASSET_ID, END, END.plusHours(2), threshold))
                .as("cooldown: adjacent non-overlapping slot must not be blocked")
                .isFalse();

        // 5. Older hold outside threshold (threshold = NOW + 1s) does NOT match
        assertThat(reservationHoldRepository.existsRecentHoldOverlap(USER_ID, ASSET_ID, START, END, NOW.plusSeconds(1)))
                .as("cooldown: hold created before threshold must not trigger cooldown")
                .isFalse();

        // 6. Different user does NOT match
        assertThat(reservationHoldRepository.existsRecentHoldOverlap(999L, ASSET_ID, START, END, threshold))
                .as("cooldown: another student's recent hold must not trigger cooldown for this user")
                .isFalse();

        // 7. Different asset does NOT match
        assertThat(reservationHoldRepository.existsRecentHoldOverlap(USER_ID, 999L, START, END, threshold))
                .as("cooldown: recent hold on another asset must not trigger cooldown")
                .isFalse();
    }
}

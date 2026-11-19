package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.catalog.api.AssetRetiredEvent;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for the W7 asset retirement reassignment flow (#245): verifies that
 * {@link ReassignmentService#handle(AssetRetiredEvent)} reassigns upcoming bookings of a retired
 * asset to a free substitute (or force-cancels when none exists) and persists {@code reassignment_log}
 * rows with {@code incident_id = NULL} via the Flyway schema migration.
 */
@SpringBootTest
class AssetRetiredReassignmentIT {

    private static final long STUDENT_ID = 301L;
    private static final long ADMIN_ID = 302L;
    private static final long CATEGORY_ID = 750L;
    private static final long LAB_ROOM_ID = 750L;
    private static final long RETIRED_ASSET_ID = 751L;
    private static final long SUBSTITUTE_ASSET_ID = 752L;

    @Autowired
    private ReassignmentService reassignmentService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReassignmentLogRepository reassignmentLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-19T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
                VALUES (301, 'student301@retire.test', '{bcrypt}hash', 'Student 301', 'STUDENT', true, NOW()),
                       (302, 'admin302@retire.test', '{bcrypt}hash', 'Admin 302', 'ADMIN', true, NOW())
                ON CONFLICT (id) DO NOTHING
                """);
        jdbcTemplate.update(
                "INSERT INTO categories (id, name) VALUES (750, 'Retire Test Scopes') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update(
                "INSERT INTO lab_rooms (id, code, name) VALUES (750, 'LAB-750', 'Retire Test Lab') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update("""
                INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                VALUES (751, 'Retired Scope #1', 'RETIRE-DS1054Z', 'SN-RET-751', 'RETIRED', 750, 750, NOW(), NOW()),
                       (752, 'Healthy Scope #2', 'RETIRE-DS1054Z', 'SN-RET-752', 'AVAILABLE', 750, 750, NOW(), NOW())
                ON CONFLICT (id) DO NOTHING
                """);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "DELETE FROM reassignment_log WHERE from_asset_id IN (?, ?)", RETIRED_ASSET_ID, SUBSTITUTE_ASSET_ID);
        jdbcTemplate.update(
                "DELETE FROM reservations WHERE asset_id IN (?, ?) OR original_asset_id IN (?, ?)",
                RETIRED_ASSET_ID,
                SUBSTITUTE_ASSET_ID,
                RETIRED_ASSET_ID,
                SUBSTITUTE_ASSET_ID);
        jdbcTemplate.update("DELETE FROM assets WHERE id IN (?, ?)", RETIRED_ASSET_ID, SUBSTITUTE_ASSET_ID);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", LAB_ROOM_ID);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", CATEGORY_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", STUDENT_ID, ADMIN_ID);
    }

    @Test
    @DisplayName("Retire flow (#245): moves upcoming booking to free same-model substitute and logs null incident_id")
    void assetRetiredReassignsUpcomingBookingAndPersistsNullIncidentId() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 19, 9, 0);
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 20, 16, 0);

        Reservation saved =
                reservationRepository.save(new Reservation(STUDENT_ID, RETIRED_ASSET_ID, start, end, createdAt));

        reassignmentService.handle(
                new AssetRetiredEvent(RETIRED_ASSET_ID, LocalDateTime.of(2026, 11, 19, 10, 0), ADMIN_ID));

        Reservation updated = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("reassigned reservation must stay CONFIRMED; see ReassignmentService.handle(AssetRetiredEvent)")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(updated.getAssetId())
                .as("reservation must move to the free same-model substitute; see ReassignmentBookingProcessor")
                .isEqualTo(SUBSTITUTE_ASSET_ID);
        assertThat(updated.getOriginalAssetId())
                .as("originalAssetId must preserve the retired asset id")
                .isEqualTo(RETIRED_ASSET_ID);

        List<ReassignmentLog> logs = reassignmentLogRepository.findAll().stream()
                .filter(l -> l.getReservationId().equals(saved.getId()))
                .toList();
        assertThat(logs)
                .as("exactly one reassignment_log row must be persisted for the retired-asset move")
                .hasSize(1);
        ReassignmentLog logEntry = logs.get(0);
        assertThat(logEntry.getIncidentId())
                .as("administrative asset retirement has no incident report, so incident_id must be NULL (#245)")
                .isNull();
        assertThat(logEntry.getFromAssetId()).isEqualTo(RETIRED_ASSET_ID);
        assertThat(logEntry.getToAssetId()).isEqualTo(SUBSTITUTE_ASSET_ID);
        assertThat(logEntry.getOutcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(logEntry.getStrategy()).isEqualTo("SameModelStrategy");
    }

    @Test
    @DisplayName(
            "Retire flow (#245): force-cancels upcoming booking when no substitute is free and logs null incident_id")
    void assetRetiredForceCancelsWhenNoSubstituteFreeAndPersistsNullIncidentId() {
        // Retire the substitute too so no replacement asset is available
        jdbcTemplate.update("UPDATE assets SET status = 'RETIRED' WHERE id = ?", SUBSTITUTE_ASSET_ID);

        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 19, 9, 0);
        LocalDateTime start = LocalDateTime.of(2026, 11, 20, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 20, 16, 0);

        Reservation saved =
                reservationRepository.save(new Reservation(STUDENT_ID, RETIRED_ASSET_ID, start, end, createdAt));

        reassignmentService.handle(
                new AssetRetiredEvent(RETIRED_ASSET_ID, LocalDateTime.of(2026, 11, 19, 10, 0), ADMIN_ID));

        Reservation updated = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("when no substitute is available on retire, booking must be FORCE_CANCELLED; "
                        + "see ReassignmentBookingProcessor")
                .isEqualTo(ReservationStatus.FORCE_CANCELLED);

        List<ReassignmentLog> logs = reassignmentLogRepository.findAll().stream()
                .filter(l -> l.getReservationId().equals(saved.getId()))
                .toList();
        assertThat(logs)
                .as("exactly one FORCE_CANCELLED reassignment_log row must be persisted")
                .hasSize(1);
        ReassignmentLog logEntry = logs.get(0);
        assertThat(logEntry.getIncidentId())
                .as("administrative asset retirement force-cancel must log incident_id = NULL (#245)")
                .isNull();
        assertThat(logEntry.getFromAssetId()).isEqualTo(RETIRED_ASSET_ID);
        assertThat(logEntry.getToAssetId()).isNull();
        assertThat(logEntry.getOutcome()).isEqualTo(ReassignmentOutcome.FORCE_CANCELLED);
        assertThat(logEntry.getStrategy()).isNull();
    }
}

package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.catalog.api.AssetRetiredEvent;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.domain.ReassignmentTrigger;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration test for the W7 asset retirement reassignment flow (#245): verifies both direct
 * {@link ReassignmentService#handle(AssetRetiredEvent)} execution and the full HTTP
 * {@code POST /api/v1/admin/assets/{id}/retire} → {@code AFTER_COMMIT} → {@link LocalAssetRetiredListener}
 * wiring, asserting {@code incident_id = NULL} and {@code trigger = RETIRED} in {@code reassignment_log}.
 */
@SpringBootTest
@AutoConfigureMockMvc
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

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
                "DELETE FROM audit_logs WHERE actor_id IN (?, ?) OR (entity_type = 'Asset' AND entity_id IN (?, ?)) "
                        + "OR (entity_type = 'Reservation' AND entity_id IN "
                        + "(SELECT id FROM reservations WHERE asset_id IN (?, ?) OR original_asset_id IN (?, ?)))",
                STUDENT_ID,
                ADMIN_ID,
                RETIRED_ASSET_ID,
                SUBSTITUTE_ASSET_ID,
                RETIRED_ASSET_ID,
                SUBSTITUTE_ASSET_ID,
                RETIRED_ASSET_ID,
                SUBSTITUTE_ASSET_ID);
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
    @DisplayName("Retire flow (#245): moves upcoming booking to free same-model substitute and logs RETIRED trigger")
    void assetRetiredReassignsUpcomingBookingAndPersistsNullIncidentId() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = now.minusHours(1);
        LocalDateTime start = now.plusDays(1).withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        Reservation saved =
                reservationRepository.save(new Reservation(STUDENT_ID, RETIRED_ASSET_ID, start, end, createdAt));

        reassignmentService.handle(new AssetRetiredEvent(RETIRED_ASSET_ID, now, ADMIN_ID));

        Reservation updated = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("reassigned reservation must stay CONFIRMED; see ReassignmentService.handle(AssetRetiredEvent)")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(updated.getAssetId())
                .as("reservation must move to the free same-model substitute; see ReassignmentBookingProcessor")
                .isEqualTo(SUBSTITUTE_ASSET_ID);
        assertThat(updated.getOriginalAssetId())
                .as("originalAssetId must preserve the retired asset id; see Reservation.moveTo")
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
        assertThat(logEntry.getTrigger())
                .as("retirement reassignment log row must record trigger = RETIRED (#245)")
                .isEqualTo(ReassignmentTrigger.RETIRED);
        assertThat(logEntry.getFromAssetId())
                .as("fromAssetId must record the retired asset id")
                .isEqualTo(RETIRED_ASSET_ID);
        assertThat(logEntry.getToAssetId())
                .as("toAssetId must record the substitute asset id")
                .isEqualTo(SUBSTITUTE_ASSET_ID);
        assertThat(logEntry.getOutcome())
                .as("outcome must be REASSIGNED when a substitute is free")
                .isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(logEntry.getStrategy())
                .as("same-model substitute must be chosen by SameModelStrategy")
                .isEqualTo("SameModelStrategy");
    }

    @Test
    @DisplayName("Retire flow (#245): force-cancels upcoming booking when no substitute is free and logs RETIRED")
    void assetRetiredForceCancelsWhenNoSubstituteFreeAndPersistsNullIncidentId() {
        // Retire the substitute too so no replacement asset is available
        jdbcTemplate.update("UPDATE assets SET status = 'RETIRED' WHERE id = ?", SUBSTITUTE_ASSET_ID);

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = now.minusHours(1);
        LocalDateTime start = now.plusDays(1).withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        Reservation saved =
                reservationRepository.save(new Reservation(STUDENT_ID, RETIRED_ASSET_ID, start, end, createdAt));

        reassignmentService.handle(new AssetRetiredEvent(RETIRED_ASSET_ID, now, ADMIN_ID));

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
        assertThat(logEntry.getTrigger())
                .as("retirement force-cancel log row must record trigger = RETIRED (#245)")
                .isEqualTo(ReassignmentTrigger.RETIRED);
        assertThat(logEntry.getFromAssetId())
                .as("fromAssetId must record the retired asset id")
                .isEqualTo(RETIRED_ASSET_ID);
        assertThat(logEntry.getToAssetId())
                .as("toAssetId must be null when force-cancelled")
                .isNull();
        assertThat(logEntry.getOutcome())
                .as("outcome must be FORCE_CANCELLED when no substitute is free")
                .isEqualTo(ReassignmentOutcome.FORCE_CANCELLED);
        assertThat(logEntry.getStrategy())
                .as("strategy must be null when force-cancelled")
                .isNull();
    }

    @Test
    @DisplayName("Retire flow (#245): POST /api/v1/admin/assets/{id}/retire triggers LocalAssetRetiredListener")
    void retiringAssetViaAdminEndpointReassignsBookingAfterCommit() throws Exception {
        // Start with the asset AVAILABLE so AssetService.retire performs a real transition and publishes the event.
        jdbcTemplate.update("UPDATE assets SET status = 'AVAILABLE' WHERE id = ?", RETIRED_ASSET_ID);

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = now.minusHours(1);
        LocalDateTime start = now.plusDays(1).withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        Reservation saved =
                reservationRepository.save(new Reservation(STUDENT_ID, RETIRED_ASSET_ID, start, end, createdAt));

        // AsyncConfig runs @Async on the caller's thread in tests, so LocalAssetRetiredListener has
        // already finished when the HTTP retire call returns.
        mockMvc.perform(post("/api/v1/admin/assets/{id}/retire", RETIRED_ASSET_ID)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(ADMIN_ID)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        Reservation updated = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getAssetId())
                .as("real AssetService.retire must fire LocalAssetRetiredListener AFTER_COMMIT and move the booking; "
                        + "see LocalAssetRetiredListener.on")
                .isEqualTo(SUBSTITUTE_ASSET_ID);
        assertThat(updated.getOriginalAssetId())
                .as("originalAssetId must preserve the retired asset id after listener-driven reassignment")
                .isEqualTo(RETIRED_ASSET_ID);

        List<ReassignmentLog> logs = reassignmentLogRepository.findAll().stream()
                .filter(l -> l.getReservationId().equals(saved.getId()))
                .toList();
        assertThat(logs)
                .as("listener-driven retirement must persist one RETIRED reassignment_log row")
                .hasSize(1);
        assertThat(logs.get(0).getIncidentId())
                .as("listener-driven retirement must leave incident_id NULL")
                .isNull();
        assertThat(logs.get(0).getTrigger())
                .as("listener-driven retirement must record trigger = RETIRED")
                .isEqualTo(ReassignmentTrigger.RETIRED);
    }
}

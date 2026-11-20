package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.catalog.api.AssetRetiredEvent;
import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.domain.ReassignmentTrigger;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.api.ReservationSummary;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
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
 * wiring, asserting {@code incident_id = NULL} and {@code triggered_by = RETIRED} in {@code reassignment_log}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AssetRetiredReassignmentIT {

    @Autowired
    private ReassignmentService reassignmentService;

    @Autowired
    private ReassignmentBookingProcessor reassignmentBookingProcessor;

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

    // Generated per-test IDs so tearDown never touches rows owned by any other test.
    private Long studentId;
    private Long adminId;
    private Long categoryId;
    private Long labRoomId;
    private Long retiredAssetId;
    private Long substituteAssetId;
    private String assetModel;

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
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String model = "RETIRE-DS1054Z-" + tag;
        this.assetModel = model;

        studentId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, '{bcrypt}hash', 'Retire Test Student', 'STUDENT', true, NOW())
                RETURNING id
                """, Long.class, "student-" + tag + "@retire.test");

        adminId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, '{bcrypt}hash', 'Retire Test Admin', 'ADMIN', true, NOW())
                RETURNING id
                """, Long.class, "admin-" + tag + "@retire.test");

        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class, "Retire Test Scopes " + tag);

        labRoomId = jdbcTemplate.queryForObject(
                "INSERT INTO lab_rooms (code, name) VALUES (?, ?) RETURNING id",
                Long.class,
                "LAB-" + tag,
                "Retire Test Lab " + tag);

        retiredAssetId = jdbcTemplate.queryForObject(
                """
                INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                VALUES (?, ?, ?, 'RETIRED', ?, ?, NOW(), NOW())
                RETURNING id
                """, Long.class, "Retired Scope " + tag, model, "SN-RET-1-" + tag, categoryId, labRoomId);

        substituteAssetId = jdbcTemplate.queryForObject(
                """
                INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                VALUES (?, ?, ?, 'AVAILABLE', ?, ?, NOW(), NOW())
                RETURNING id
                """, Long.class, "Healthy Scope " + tag, model, "SN-RET-2-" + tag, categoryId, labRoomId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "DELETE FROM reassignment_log WHERE from_asset_id IN (?, ?)", retiredAssetId, substituteAssetId);
        jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE actor_id IN (?, ?) OR (entity_type = 'Asset' AND entity_id IN (?, ?)) "
                        + "OR (entity_type = 'Reservation' AND entity_id IN "
                        + "(SELECT id FROM reservations WHERE asset_id IN (?, ?) OR original_asset_id IN (?, ?)))",
                studentId,
                adminId,
                retiredAssetId,
                substituteAssetId,
                retiredAssetId,
                substituteAssetId,
                retiredAssetId,
                substituteAssetId);
        jdbcTemplate.update(
                "DELETE FROM reservations WHERE asset_id IN (?, ?) OR original_asset_id IN (?, ?)",
                retiredAssetId,
                substituteAssetId,
                retiredAssetId,
                substituteAssetId);
        jdbcTemplate.update("DELETE FROM assets WHERE id IN (?, ?)", retiredAssetId, substituteAssetId);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", studentId, adminId);
    }

    @Test
    @DisplayName("Retire flow (#245): moves upcoming booking to free same-model substitute and logs RETIRED trigger")
    void assetRetiredReassignsUpcomingBookingAndPersistsNullIncidentId() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = now.minusHours(1);
        LocalDateTime start = now.plusDays(1).withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        Reservation saved =
                reservationRepository.save(new Reservation(studentId, retiredAssetId, start, end, createdAt));

        reassignmentService.handle(new AssetRetiredEvent(retiredAssetId, now, adminId));

        Reservation updated = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getStatus())
                .as("reassigned reservation must stay CONFIRMED; see ReassignmentService.handle(AssetRetiredEvent)")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(updated.getAssetId())
                .as("reservation must move to the free same-model substitute; see ReassignmentBookingProcessor")
                .isEqualTo(substituteAssetId);
        assertThat(updated.getOriginalAssetId())
                .as("originalAssetId must preserve the retired asset id; see Reservation.moveTo")
                .isEqualTo(retiredAssetId);

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
                .isEqualTo(retiredAssetId);
        assertThat(logEntry.getToAssetId())
                .as("toAssetId must record the chosen substitute asset id")
                .isEqualTo(substituteAssetId);
        assertThat(logEntry.getOutcome())
                .as("outcome must be REASSIGNED when a substitute is available")
                .isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(logEntry.getStrategy())
                .as("same-model substitute must be selected by SameModelStrategy")
                .isEqualTo("SameModelStrategy");
    }

    @Test
    @DisplayName("Retire flow (#245): force-cancels upcoming booking when no substitute is free")
    void assetRetiredForceCancelsWhenNoSubstituteAvailableAndPersistsNullIncidentId() {
        // Retire the substitute too so no replacement asset is available
        jdbcTemplate.update("UPDATE assets SET status = 'RETIRED' WHERE id = ?", substituteAssetId);

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = now.minusHours(1);
        LocalDateTime start = now.plusDays(1).withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        Reservation saved =
                reservationRepository.save(new Reservation(studentId, retiredAssetId, start, end, createdAt));

        reassignmentService.handle(new AssetRetiredEvent(retiredAssetId, now, adminId));

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
                .isEqualTo(retiredAssetId);
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
        jdbcTemplate.update("UPDATE assets SET status = 'AVAILABLE' WHERE id = ?", retiredAssetId);

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = now.minusHours(1);
        LocalDateTime start = now.plusDays(1).withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        Reservation saved =
                reservationRepository.save(new Reservation(studentId, retiredAssetId, start, end, createdAt));

        // AsyncConfig runs @Async on the caller's thread in tests, so LocalAssetRetiredListener has
        // already finished when the HTTP retire call returns.
        mockMvc.perform(post("/api/v1/admin/assets/{id}/retire", retiredAssetId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        Reservation updated = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated.getAssetId())
                .as("real AssetService.retire must fire LocalAssetRetiredListener AFTER_COMMIT and move the booking; "
                        + "see LocalAssetRetiredListener.on")
                .isEqualTo(substituteAssetId);
        assertThat(updated.getOriginalAssetId())
                .as("originalAssetId must preserve the retired asset id after listener-driven reassignment")
                .isEqualTo(retiredAssetId);

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

    @Test
    @DisplayName(
            "Concurrent incident + retire guard (#319): stale snapshot after first move logs only one reassignment and never double-moves or force-cancels")
    void staleConcurrentHandlerAfterMoveDoesNotLogDuplicateMoveOrForceCancel() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = now.minusHours(1);
        LocalDateTime start = now.plusDays(1).withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        Reservation saved =
                reservationRepository.save(new Reservation(studentId, retiredAssetId, start, end, createdAt));

        // Capture the booking snapshot BEFORE the first handler runs (simulating two handlers that
        // both called findUpcomingActiveByAsset before either committed its move).
        ReservationSummary staleSnapshot = new ReservationSummary(
                saved.getId(), studentId, retiredAssetId, start, end, ReservationStatus.CONFIRMED);
        AssetSummary sourceAsset = new AssetSummary(
                retiredAssetId, "Retired Scope #1", assetModel, categoryId, labRoomId, AssetStatus.RETIRED);

        // First handler moves the booking from retiredAssetId to substituteAssetId.
        reassignmentService.handle(new AssetRetiredEvent(retiredAssetId, now, adminId));

        // Second handler arrives late with its pre-move snapshot of the same booking on retiredAssetId.
        var secondResult = reassignmentBookingProcessor.processSingleBooking(
                new AssetRetiredEvent(retiredAssetId, now, adminId), staleSnapshot, sourceAsset);

        assertThat(secondResult)
                .as("second handler with stale snapshot must skip the already-moved booking; "
                        + "see ReservationService.moveToAsset / forceCancel (#319)")
                .isEmpty();

        Reservation afterBoth = reservationRepository.findById(saved.getId()).orElseThrow();
        assertThat(afterBoth.getStatus())
                .as("booking moved by the first handler must stay CONFIRMED")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(afterBoth.getAssetId())
                .as("booking must remain on substituteAssetId")
                .isEqualTo(substituteAssetId);

        List<ReassignmentLog> logs = reassignmentLogRepository.findAll().stream()
                .filter(l -> l.getReservationId().equals(saved.getId()))
                .toList();
        assertThat(logs)
                .as("concurrent handlers targeting the same asset must produce exactly one reassignment_log row (#319)")
                .hasSize(1);
    }
}

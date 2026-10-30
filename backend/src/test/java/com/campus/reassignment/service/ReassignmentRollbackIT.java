package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.security.CurrentUser;
import com.campus.common.time.ClockConfig;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.service.ReservationService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
    ReassignmentService.class,
    ReservationService.class,
    SameModelStrategy.class,
    SameCategoryStrategy.class,
    ClockConfig.class
})
class ReassignmentRollbackIT {

    private static final Long DAMAGED_ASSET_ID = 501L;
    private static final Long SUB_MODEL_BUSY_ID = 502L;
    private static final Long SUB_CAT_FREE_ID = 503L;
    private static final Long INCIDENT_ID = 888L;
    private static final Long USER_ID = 100L;

    @Autowired
    private ReassignmentService reassignmentService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReassignmentLogRepository reassignmentLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private CatalogApi catalogApi;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private CurrentUser currentUser;

    @MockitoBean
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        // Seed FK rows
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (100, 'itstudent@campus.test', '{bcrypt}hash', 'IT Student', 'STUDENT', true, NOW())
            ON CONFLICT (id) DO NOTHING
            """);
        jdbcTemplate.update("""
            INSERT INTO categories (id, name)
            VALUES (500, 'Test IT Scopes')
            ON CONFLICT (id) DO NOTHING
            """);
        jdbcTemplate.update("""
            INSERT INTO lab_rooms (id, code, name)
            VALUES (500, 'LAB-500', 'IT Lab')
            ON CONFLICT (id) DO NOTHING
            """);

        for (long id : List.of(DAMAGED_ASSET_ID, SUB_MODEL_BUSY_ID, SUB_CAT_FREE_ID)) {
            jdbcTemplate.update(
                    """
                INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'AVAILABLE', 500, 500, NOW(), NOW())
                ON CONFLICT (id) DO NOTHING
                """, id, "Asset " + id, id == SUB_CAT_FREE_ID ? "DIFFERENT_MODEL" : "SAME_MODEL", "SN-IT-" + id);
        }

        when(catalogApi.isBookable(anyLong())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        reassignmentLogRepository.deleteAll();
        reservationRepository.deleteAll();
    }

    @Test
    @DisplayName("Rollback bug fix verification: When first substitute throws SubstituteNoLongerFreeException, "
            + "the transaction does not roll back, moves to second substitute and saves log")
    void fallsBackToNextStrategyWithoutRollback() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        // Reservation to reassign (on damaged asset), committed since test runs with NOT_SUPPORTED
        Reservation bookingToMove =
                new Reservation(USER_ID, DAMAGED_ASSET_ID, start, end, LocalDateTime.of(2026, 11, 2, 10, 0));
        bookingToMove = reservationRepository.saveAndFlush(bookingToMove);

        AssetSummary damaged =
                new AssetSummary(DAMAGED_ASSET_ID, "Damaged", "SAME_MODEL", 500L, 500L, AssetStatus.AVAILABLE);
        AssetSummary sub1Busy =
                new AssetSummary(SUB_MODEL_BUSY_ID, "Sub 1 Busy", "SAME_MODEL", 500L, 500L, AssetStatus.AVAILABLE);
        AssetSummary sub2Free =
                new AssetSummary(SUB_CAT_FREE_ID, "Sub 2 Free", "DIFFERENT_MODEL", 500L, 500L, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damaged);
        // Strategy 1 (SameModel) selects sub1Busy (isAssetFree is true),
        // but we simulate a race where sub1Busy became non-bookable right before moveToAsset locked it.
        when(catalogApi.findAvailableByModel("SAME_MODEL", DAMAGED_ASSET_ID)).thenReturn(List.of(sub1Busy));
        when(catalogApi.isBookable(SUB_MODEL_BUSY_ID)).thenReturn(false);
        // Strategy 2 (SameCategory) selects sub2Free, which succeeds in moveToAsset.
        when(catalogApi.findAvailableByCategory(500L, DAMAGED_ASSET_ID)).thenReturn(List.of(sub2Free));
        when(catalogApi.isBookable(SUB_CAT_FREE_ID)).thenReturn(true);

        AssetDamagedEvent event =
                new AssetDamagedEvent(INCIDENT_ID, DAMAGED_ASSET_ID, USER_ID, Instant.parse("2026-11-02T10:00:00Z"));

        // This call invokes ReservationService.moveToAsset(bookingId, SUB_MODEL_BUSY_ID),
        // which throws SubstituteNoLongerFreeException through the real Spring proxy.
        // With noRollbackFor, Spring does NOT mark the REQUIRES_NEW transaction rollback-only.
        // It then catches the exception, evaluates SameCategoryStrategy, and moves to SUB_CAT_FREE_ID.
        reassignmentService.handle(event);

        // Assert reservation was moved to SUB_CAT_FREE_ID
        Reservation updated =
                reservationRepository.findById(bookingToMove.getId()).orElseThrow();
        assertThat(updated.getAssetId()).isEqualTo(SUB_CAT_FREE_ID);
        assertThat(updated.getOriginalAssetId()).isEqualTo(DAMAGED_ASSET_ID);
        assertThat(updated.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // Assert reassignment_log row was committed to DB
        List<ReassignmentLog> logs = reassignmentLogRepository.findAll();
        assertThat(logs).hasSize(1);
        ReassignmentLog entry = logs.get(0);
        assertThat(entry.getIncidentId()).isEqualTo(INCIDENT_ID);
        assertThat(entry.getReservationId()).isEqualTo(bookingToMove.getId());
        assertThat(entry.getFromAssetId()).isEqualTo(DAMAGED_ASSET_ID);
        assertThat(entry.getToAssetId()).isEqualTo(SUB_CAT_FREE_ID);
        assertThat(entry.getOutcome()).isEqualTo(ReassignmentOutcome.REASSIGNED);
        assertThat(entry.getStrategy()).isEqualTo("SameCategoryStrategy");
    }
}

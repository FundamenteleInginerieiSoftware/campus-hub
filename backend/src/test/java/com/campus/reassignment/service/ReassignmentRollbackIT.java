package com.campus.reassignment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.AssetStatus;
import com.campus.catalog.api.AssetSummary;
import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.security.CurrentUser;
import com.campus.incident.api.AssetDamagedEvent;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.domain.ReassignmentLog;
import com.campus.reassignment.domain.ReassignmentOutcome;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.service.ReservationService;
import com.campus.reservation.service.TransferService;
import com.campus.user.api.UserApi;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
    ReassignmentService.class,
    ReassignmentBookingProcessor.class,
    ReservationService.class,
    TransferService.class,
    SameModelStrategy.class,
    SameCategoryStrategy.class,
    ReassignmentRollbackIT.TestClockConfig.class
})
class ReassignmentRollbackIT {

    // Bug fixed: The test was importing the real ClockConfig.class (system clock) with a hard-coded
    // slot on 2026-11-02. When real calendar time passed 2026-11-02 16:00, ReassignmentService skipped
    // the slot as past (findUpcomingActiveByAsset), leaving the reservation un-reassigned.
    // Fix: Inject a fixed Clock at 2026-11-02 08:00 Europe/Bucharest via @TestConfiguration (AGENTS.md §5).
    @TestConfiguration
    static class TestClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        }
    }

    private static final Long INCIDENT_ID = 888L;

    // Generated per-test IDs so concurrent or interrupted runs never collide on users_pkey (#346).
    private Long DAMAGED_ASSET_ID;
    private Long SUB_MODEL_BUSY_ID;
    private Long SUB_CAT_FREE_ID;
    private Long SUB_MODEL_SECOND_FREE_ID;
    private Long USER_ID;
    private Long categoryId;
    private Long labRoomId;

    @Autowired
    private ReassignmentService reassignmentService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReassignmentLogRepository reassignmentLogRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private CatalogApi catalogApi;

    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private CurrentUser currentUser;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private UserApi userApi;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        USER_ID = jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
            VALUES (?, '{bcrypt}hash', 'IT Student', 'STUDENT', true, NOW())
            RETURNING id
            """, Long.class, "itstudent-" + tag + "@rollback.test");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class, "Test IT Scopes " + tag);
        labRoomId = jdbcTemplate.queryForObject(
                "INSERT INTO lab_rooms (code, name) VALUES (?, ?) RETURNING id",
                Long.class,
                "LAB-" + tag,
                "IT Lab " + tag);

        DAMAGED_ASSET_ID = insertAsset("Damaged " + tag, "SAME_MODEL", "SN-IT-1-" + tag);
        SUB_MODEL_BUSY_ID = insertAsset("Sub Busy " + tag, "SAME_MODEL", "SN-IT-2-" + tag);
        SUB_CAT_FREE_ID = insertAsset("Sub Cat Free " + tag, "DIFFERENT_MODEL", "SN-IT-3-" + tag);
        SUB_MODEL_SECOND_FREE_ID = insertAsset("Sub Model Free " + tag, "SAME_MODEL", "SN-IT-4-" + tag);

        when(catalogApi.isBookable(anyLong())).thenReturn(true);
    }

    private Long insertAsset(String name, String model, String serialNumber) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (?, ?, ?, 'AVAILABLE', ?, ?, NOW(), NOW())
            RETURNING id
            """, Long.class, name, model, serialNumber, categoryId, labRoomId);
    }

    @AfterEach
    void tearDown() {
        reassignmentLogRepository.deleteAll();
        reservationRepository.deleteAll();
        jdbcTemplate.update(
                "DELETE FROM assets WHERE id IN (?, ?, ?, ?)",
                DAMAGED_ASSET_ID,
                SUB_MODEL_BUSY_ID,
                SUB_CAT_FREE_ID,
                SUB_MODEL_SECOND_FREE_ID);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", USER_ID);
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

        AssetSummary damaged = new AssetSummary(
                DAMAGED_ASSET_ID, "Damaged", "SAME_MODEL", categoryId, labRoomId, AssetStatus.AVAILABLE);
        AssetSummary sub1Busy = new AssetSummary(
                SUB_MODEL_BUSY_ID, "Sub 1 Busy", "SAME_MODEL", categoryId, labRoomId, AssetStatus.AVAILABLE);
        AssetSummary sub2Free = new AssetSummary(
                SUB_CAT_FREE_ID, "Sub 2 Free", "DIFFERENT_MODEL", categoryId, labRoomId, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damaged);
        // Strategy 1 (SameModel) selects sub1Busy (isAssetFree is true),
        // but we simulate a race where sub1Busy became non-bookable right before moveToAsset locked it.
        when(catalogApi.findAvailableByModel("SAME_MODEL", DAMAGED_ASSET_ID)).thenReturn(List.of(sub1Busy));
        when(catalogApi.isBookable(SUB_MODEL_BUSY_ID)).thenReturn(false);
        // Strategy 2 (SameCategory) selects sub2Free, which succeeds in moveToAsset.
        when(catalogApi.findAvailableByCategory(categoryId, DAMAGED_ASSET_ID)).thenReturn(List.of(sub2Free));
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

    @Test
    @DisplayName("Bug #112 fix: Concurrent cancellation on booking 2 does not roll back booking 1 reassignment")
    void concurrentModificationOnOneBookingDoesNotRollbackOtherReassignments() {
        LocalDateTime start1 = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end1 = LocalDateTime.of(2026, 11, 2, 16, 0);
        LocalDateTime start2 = LocalDateTime.of(2026, 11, 2, 16, 0);
        LocalDateTime end2 = LocalDateTime.of(2026, 11, 2, 18, 0);

        Reservation b1 = reservationRepository.saveAndFlush(
                new Reservation(USER_ID, DAMAGED_ASSET_ID, start1, end1, LocalDateTime.of(2026, 11, 2, 10, 0)));
        Reservation b2 = reservationRepository.saveAndFlush(
                new Reservation(USER_ID, DAMAGED_ASSET_ID, start2, end2, LocalDateTime.of(2026, 11, 2, 10, 0)));

        AssetSummary damaged = new AssetSummary(
                DAMAGED_ASSET_ID, "Damaged", "SAME_MODEL", categoryId, labRoomId, AssetStatus.AVAILABLE);
        AssetSummary subFree = new AssetSummary(
                SUB_CAT_FREE_ID, "Sub Free", "DIFFERENT_MODEL", categoryId, labRoomId, AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damaged);
        when(catalogApi.findAvailableByModel(any(), anyLong())).thenReturn(List.of());
        when(catalogApi.findAvailableByCategory(categoryId, DAMAGED_ASSET_ID)).thenReturn(List.of(subFree));
        when(catalogApi.isBookable(anyLong())).thenReturn(true);

        // Simulate student concurrently cancelling b2 in a separate committed transaction
        // while b1's notification is being dispatched (exact #112 race condition)
        TransactionTemplate tt = new TransactionTemplate(transactionManager);
        tt.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        doAnswer(invocation -> {
                    tt.execute(status -> {
                        Reservation r2 =
                                reservationRepository.findById(b2.getId()).orElseThrow();
                        r2.cancel(LocalDateTime.of(2026, 11, 2, 12, 0), "Cancelled concurrently by student");
                        reservationRepository.saveAndFlush(r2);
                        return null;
                    });
                    return null;
                })
                .when(notificationService)
                .notifyUser(eq(USER_ID), eq("Reservation Reassigned"), anyString());

        AssetDamagedEvent event =
                new AssetDamagedEvent(INCIDENT_ID, DAMAGED_ASSET_ID, USER_ID, Instant.parse("2026-11-02T10:00:00Z"));

        reassignmentService.handle(event);

        // Booking 1 was moved to SUB_CAT_FREE_ID and its log is committed
        Reservation updatedB1 = reservationRepository.findById(b1.getId()).orElseThrow();
        assertThat(updatedB1.getAssetId()).isEqualTo(SUB_CAT_FREE_ID);
        assertThat(updatedB1.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        // Booking 2 remained CANCELLED (skipped cleanly)
        Reservation updatedB2 = reservationRepository.findById(b2.getId()).orElseThrow();
        assertThat(updatedB2.getStatus()).isEqualTo(ReservationStatus.CANCELLED);

        // Exactly 1 log entry (for b1), 0 for b2
        List<ReassignmentLog> logs = reassignmentLogRepository.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getReservationId()).isEqualTo(b1.getId());

        // Exactly 1 notification was sent (for b1)
        verify(notificationService, times(1)).notifyUser(eq(USER_ID), eq("Reservation Reassigned"), anyString());
    }

    @Test
    @DisplayName(
            "Bug #149 fix: When first same-model candidate becomes unavailable concurrently, tries second same-model candidate in a fresh REQUIRES_NEW transaction")
    void triesSecondSameModelCandidateWhenFirstRaces() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);

        Reservation bookingToMove = reservationRepository.saveAndFlush(
                new Reservation(USER_ID, DAMAGED_ASSET_ID, start, end, LocalDateTime.of(2026, 11, 2, 10, 0)));

        AssetSummary damaged = new AssetSummary(
                DAMAGED_ASSET_ID, "Damaged", "SAME_MODEL", categoryId, labRoomId, AssetStatus.AVAILABLE);
        AssetSummary sub1Raced = new AssetSummary(
                SUB_MODEL_BUSY_ID, "Sub 1 Raced", "SAME_MODEL", categoryId, labRoomId, AssetStatus.AVAILABLE);
        AssetSummary sub2SameModelFree = new AssetSummary(
                SUB_MODEL_SECOND_FREE_ID,
                "Sub 2 Same Model Free",
                "SAME_MODEL",
                categoryId,
                labRoomId,
                AssetStatus.AVAILABLE);

        when(catalogApi.getAsset(DAMAGED_ASSET_ID)).thenReturn(damaged);
        when(catalogApi.findAvailableByModel("SAME_MODEL", DAMAGED_ASSET_ID))
                .thenReturn(List.of(sub1Raced, sub2SameModelFree));
        when(catalogApi.isBookable(SUB_MODEL_BUSY_ID)).thenReturn(false);
        when(catalogApi.isBookable(SUB_MODEL_SECOND_FREE_ID)).thenReturn(true);

        AssetDamagedEvent event =
                new AssetDamagedEvent(INCIDENT_ID, DAMAGED_ASSET_ID, USER_ID, Instant.parse("2026-11-02T10:00:00Z"));

        reassignmentService.handle(event);

        Reservation updated =
                reservationRepository.findById(bookingToMove.getId()).orElseThrow();
        assertThat(updated.getAssetId()).isEqualTo(SUB_MODEL_SECOND_FREE_ID);
        assertThat(updated.getOriginalAssetId()).isEqualTo(DAMAGED_ASSET_ID);
        assertThat(updated.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        List<ReassignmentLog> logs = reassignmentLogRepository.findAll();
        assertThat(logs).hasSize(1);
        ReassignmentLog entry = logs.get(0);
        assertThat(entry.getToAssetId()).isEqualTo(SUB_MODEL_SECOND_FREE_ID);
        assertThat(entry.getStrategy()).isEqualTo("SameModelStrategy");
    }
}

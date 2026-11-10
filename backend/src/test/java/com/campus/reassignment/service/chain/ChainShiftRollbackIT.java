package com.campus.reassignment.service.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.campus.catalog.api.CatalogApi;
import com.campus.common.audit.AuditService;
import com.campus.common.error.ConflictException;
import com.campus.common.security.CurrentUser;
import com.campus.notification.api.NotificationService;
import com.campus.reassignment.repository.ReassignmentLogRepository;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.service.ReservationService;
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
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks on a real database that a chain shift is all-or-nothing: when a later step fails, the earlier
 * moves, the log rows and the emails are all rolled back.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ChainShiftExecutor.class, ReservationService.class, ChainShiftRollbackIT.TestClockConfig.class})
class ChainShiftRollbackIT {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        }
    }

    private static final Long ASSET_X = 711L;
    private static final Long ASSET_Y = 712L;
    private static final Long ASSET_Z = 713L;

    @Autowired
    private ChainShiftExecutor chainShiftExecutor;

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

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES ('chainrollback@campus.test', '{bcrypt}hash', 'Chain Student', 'STUDENT', true, NOW())
                RETURNING id
                """, Long.class);
        jdbcTemplate.update("INSERT INTO categories (id, name) VALUES (710, 'Chain IT') ON CONFLICT (id) DO NOTHING");
        jdbcTemplate.update(
                "INSERT INTO lab_rooms (id, code, name) VALUES (710, 'LAB-710', 'Chain Lab') ON CONFLICT (id) DO NOTHING");
        for (long id : List.of(ASSET_X, ASSET_Y, ASSET_Z)) {
            jdbcTemplate.update("""
                    INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                    VALUES (?, ?, 'CHAIN_MODEL', ?, 'AVAILABLE', 710, 710, NOW(), NOW())
                    ON CONFLICT (id) DO NOTHING
                    """, id, "Asset " + id, "SN-CHAIN-" + id);
        }
    }

    @AfterEach
    void tearDown() {
        reassignmentLogRepository.deleteAll();
        reservationRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM assets WHERE id IN (?, ?, ?)", ASSET_X, ASSET_Y, ASSET_Z);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = 710");
        jdbcTemplate.update("DELETE FROM categories WHERE id = 710");
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("2-step chain whose second move fails: first move, log rows and emails are rolled back")
    void secondStepFailureRollsBackWholeChain() {
        LocalDateTime start = LocalDateTime.of(2026, 11, 2, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 2, 16, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 2, 7, 0);

        Reservation onX = reservationRepository.saveAndFlush(new Reservation(userId, ASSET_X, start, end, createdAt));
        Reservation onY = reservationRepository.saveAndFlush(new Reservation(userId, ASSET_Y, start, end, createdAt));

        // Step 1 (Y -> Z) succeeds; step 2 (X -> Y) fails because Y became non-bookable meanwhile.
        when(catalogApi.isBookable(anyLong())).thenReturn(true);
        when(catalogApi.isBookable(ASSET_Y)).thenReturn(false);

        ChainShiftPlan plan = new ChainShiftPlan(
                true,
                List.of(
                        new ChainShiftStep(onY.getId(), userId, ASSET_Y, "Y", ASSET_Z, "Z", start, end, "SameModel"),
                        new ChainShiftStep(onX.getId(), userId, ASSET_X, "X", ASSET_Y, "Y", start, end, "SameModel")),
                "test chain");

        assertThatThrownBy(() -> chainShiftExecutor.executeChainShift(plan))
                .as("a failed step must abort the chain")
                .isInstanceOf(ConflictException.class);

        assertThat(reservationRepository.findById(onY.getId()).orElseThrow().getAssetId())
                .as("step 1 must be rolled back: the booking stays on Y")
                .isEqualTo(ASSET_Y);
        assertThat(reservationRepository.findById(onX.getId()).orElseThrow().getAssetId())
                .as("the failed step leaves the booking on X")
                .isEqualTo(ASSET_X);
        assertThat(reassignmentLogRepository.findAll())
                .as("no reassignment_log row may survive the rollback")
                .isEmpty();
        verify(notificationService, never()).notifyUser(anyLong(), anyString(), anyString());
    }
}

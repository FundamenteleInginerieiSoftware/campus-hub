package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.user.api.UserDisabledEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test for {@link UserDisabledReservationListener} (#229, #302).
 * Deliberately non-{@code @Transactional} so real PostgreSQL commits fire {@code AFTER_COMMIT}, and
 * reuses the shared cached Spring context (computing dates relative to the injected {@link Clock} bean)
 * to avoid opening an extra HikariPool against PostgreSQL's connection limit.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserDisabledReservationListenerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    private Long studentId;
    private Long adminId;
    private Long categoryId;
    private Long labRoomId;
    private Long asset1Id;
    private Long asset2Id;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        studentId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, '{bcrypt}hash', 'Disabled Student', 'STUDENT', true, NOW())
                RETURNING id
                """, Long.class, "student-" + tag + "@disable-listener.test");
        adminId = jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, '{bcrypt}hash', 'Disable Admin', 'ADMIN', true, NOW())
                RETURNING id
                """, Long.class, "admin-" + tag + "@disable-listener.test");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class, "Disable Test Cat " + tag);
        labRoomId = jdbcTemplate.queryForObject(
                "INSERT INTO lab_rooms (code, name) VALUES (?, ?) RETURNING id",
                Long.class,
                "DL-" + tag,
                "Disable Test Lab " + tag);
        asset1Id = jdbcTemplate.queryForObject(
                """
                INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                VALUES (?, 'DL-MODEL', ?, 'AVAILABLE', ?, ?, NOW(), NOW())
                RETURNING id
                """, Long.class, "Disable Asset 1 " + tag, "SN-DL-1-" + tag, categoryId, labRoomId);
        asset2Id = jdbcTemplate.queryForObject(
                """
                INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                VALUES (?, 'DL-MODEL', ?, 'AVAILABLE', ?, ?, NOW(), NOW())
                RETURNING id
                """, Long.class, "Disable Asset 2 " + tag, "SN-DL-2-" + tag, categoryId, labRoomId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE actor_id IN (?, ?) OR (entity_type = 'User' AND entity_id = ?) "
                        + "OR (entity_type = 'Reservation' AND entity_id IN (SELECT id FROM reservations WHERE user_id = ?))",
                studentId,
                adminId,
                studentId,
                studentId);
        jdbcTemplate.update("DELETE FROM reservations WHERE user_id = ?", studentId);
        jdbcTemplate.update("DELETE FROM assets WHERE id IN (?, ?)", asset1Id, asset2Id);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", studentId, adminId);
    }

    @Test
    @DisplayName(
            "Disable user flow (#229, #302): POST /api/v1/admin/users/{id}/disable cancels upcoming CONFIRMED bookings and keeps CHECKED_OUT")
    void disablingUserViaAdminEndpointCancelsUpcomingConfirmedBookingsAndAuditsAdminActor() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);

        Reservation upcoming = reservationRepository.save(new Reservation(
                studentId,
                asset1Id,
                now.plusDays(1).withHour(14),
                now.plusDays(1).withHour(16),
                now));

        Reservation checkedOut =
                new Reservation(studentId, asset2Id, now.minusMinutes(10), now.plusHours(1), now.minusHours(2));
        checkedOut.checkOut(now.minusMinutes(5));
        checkedOut = reservationRepository.save(checkedOut);

        mockMvc.perform(post("/api/v1/admin/users/{id}/disable", studentId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        Reservation reloadedUpcoming =
                reservationRepository.findById(upcoming.getId()).orElseThrow();
        assertThat(reloadedUpcoming.getStatus())
                .as("disabling a user must cancel upcoming CONFIRMED bookings via UserDisabledReservationListener; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isEqualTo(ReservationStatus.CANCELLED);

        Reservation reloadedCheckedOut =
                reservationRepository.findById(checkedOut.getId()).orElseThrow();
        assertThat(reloadedCheckedOut.getStatus())
                .as("CHECKED_OUT equipment is physically with the student and must not be auto-cancelled on disable; "
                        + "see ReservationService.cancelUpcomingReservationsForUser")
                .isEqualTo(ReservationStatus.CHECKED_OUT);

        List<Long> cancelAuditActors = jdbcTemplate.queryForList(
                "SELECT actor_id FROM audit_logs WHERE action = 'CANCEL_RESERVATION' AND entity_id = ?",
                Long.class,
                upcoming.getId());
        assertThat(cancelAuditActors)
                .as("non-@Async AFTER_COMMIT listener must preserve CurrentUser so audit_logs records adminId; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .containsExactly(adminId);
    }

    @Test
    @DisplayName(
            "Disable user flow (#229): rolled-back transaction publishing UserDisabledEvent does not cancel bookings")
    void rolledBackDisableTransactionDoesNotTriggerListener() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Reservation upcoming = reservationRepository.save(new Reservation(
                studentId,
                asset1Id,
                now.plusDays(1).withHour(14),
                now.plusDays(1).withHour(16),
                now));

        transactionTemplate.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new UserDisabledEvent(studentId, adminId));
            status.setRollbackOnly();
        });

        Reservation reloaded = reservationRepository.findById(upcoming.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("AFTER_COMMIT listener must not run when the publishing transaction rolls back; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName(
            "Disable user flow (#229): listener failure keeps account disabled (200 OK) and admin can retry via cancel-upcoming")
    void listenerFailureKeepsAccountDisabledAndManualCancelUpcomingRecoversIdempotently() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Reservation upcoming = reservationRepository.save(new Reservation(
                studentId,
                asset1Id,
                now.plusDays(1).withHour(14),
                now.plusDays(1).withHour(16),
                now));

        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String fnName = "fail_cancel_user_" + suffix;
        String trigName = "trg_fail_cancel_" + suffix;

        // Force the listener's REQUIRES_NEW reservation update to fail inside PostgreSQL while letting
        // AdminUserService.disable commit normally.
        jdbcTemplate.execute("""
                CREATE FUNCTION %s() RETURNS trigger AS $$
                BEGIN
                    IF NEW.user_id = %d AND NEW.status = 'CANCELLED' THEN
                        RAISE EXCEPTION 'Simulated cancel failure for test user %d';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql;
                """.formatted(fnName, studentId, studentId));
        jdbcTemplate.execute("""
                CREATE TRIGGER %s
                BEFORE UPDATE ON reservations
                FOR EACH ROW EXECUTE FUNCTION %s();
                """.formatted(trigName, fnName));

        try {
            mockMvc.perform(post("/api/v1/admin/users/{id}/disable", studentId)
                            .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                    .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk());

            Boolean enabled =
                    jdbcTemplate.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, studentId);
            assertThat(enabled)
                    .as("account disable must remain committed even when the AFTER_COMMIT reservation listener fails; "
                            + "see UserDisabledReservationListener.onUserDisabled")
                    .isFalse();

            Reservation stillConfirmed =
                    reservationRepository.findById(upcoming.getId()).orElseThrow();
            assertThat(stillConfirmed.getStatus())
                    .as("booking stays CONFIRMED while the simulated DB trigger blocks cancellation")
                    .isEqualTo(ReservationStatus.CONFIRMED);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigName + " ON reservations");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + fnName + "()");
        }

        // Manual retry endpoint recovers cleanly and is idempotent on a second call.
        mockMvc.perform(post("/api/v1/admin/reservations/users/{userId}/cancel-upcoming", studentId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        Reservation cancelledAfterRetry =
                reservationRepository.findById(upcoming.getId()).orElseThrow();
        assertThat(cancelledAfterRetry.getStatus())
                .as("manual cancel-upcoming retry must cancel the booking once the transient failure clears; "
                        + "see ReservationService.cancelUpcomingReservationsForUser")
                .isEqualTo(ReservationStatus.CANCELLED);

        mockMvc.perform(post("/api/v1/admin/reservations/users/{userId}/cancel-upcoming", studentId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("Disable -> re-enable race (#362 §3): listener skips cancellation when user is already active in DB")
    void reEnabledUserBeforeListenerRunsKeepsUpcomingBookingConfirmed() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Reservation upcoming = reservationRepository.save(new Reservation(
                studentId,
                asset1Id,
                now.plusDays(1).withHour(14),
                now.plusDays(1).withHour(16),
                now));

        // Publish UserDisabledEvent while users.enabled = true (simulating an admin re-enabling the
        // account before the AFTER_COMMIT listener acquires lockUserQuota and re-checks findActiveRole).
        transactionTemplate.executeWithoutResult(
                status -> eventPublisher.publishEvent(new UserDisabledEvent(studentId, adminId)));

        Reservation reloaded = reservationRepository.findById(upcoming.getId()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("listener must skip cancellation when user is active under lockUserQuota; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isEqualTo(ReservationStatus.CONFIRMED);
    }
}

package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.common.error.ConflictException;
import com.campus.common.error.ForbiddenException;
import com.campus.common.error.InvalidStateTransitionException;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.domain.ReservationTransfer;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.ReservationTransferRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.ExtendReservationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrency integration test proving that pessimistic row locking (CatalogApi.lockForBooking)
 * on the assets table guarantees atomic overlap checks: when two threads attempt to book the exact
 * same slot at the exact same millisecond, exactly one wins and the second receives 409 RESERVATION_CONFLICT.
 */
@SpringBootTest
class ReservationConcurrencyIT {

    private static final Long ASSET_ID = 601L;
    private static final Long ASSET_ID_2 = 602L;
    private static final Long ASSET_ID_3 = 603L;
    private static final Long USER_1_ID = 101L;
    private static final Long USER_2_ID = 102L;
    private static final Long ADMIN_ID = 103L;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private HoldService holdService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationHoldRepository reservationHoldRepository;

    @Autowired
    private ReservationTransferRepository reservationTransferRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @TestConfiguration
    static class ConcurrencyTestConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-11-04T08:00:00Z"), ZoneId.of("Europe/Bucharest"));
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (101, 'student101@campus.test', '{bcrypt}hash', 'Student 101', 'STUDENT', true, NOW()),
                   (102, 'student102@campus.test', '{bcrypt}hash', 'Student 102', 'STUDENT', true, NOW()),
                   (103, 'admin103@campus.test', '{bcrypt}hash', 'Admin 103', 'ADMIN', true, NOW())
            ON CONFLICT (id) DO NOTHING
            """);

        jdbcTemplate.update("""
            INSERT INTO categories (id, name)
            VALUES (600, 'Oscilloscopes')
            ON CONFLICT (id) DO NOTHING
            """);

        jdbcTemplate.update("""
            INSERT INTO lab_rooms (id, code, name)
            VALUES (600, 'LAB-600', 'Physics Lab')
            ON CONFLICT (id) DO NOTHING
            """);

        jdbcTemplate.update("""
            INSERT INTO assets (id, name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (601, 'Keysight DSOX1204G #1', 'DSOX1204G', 'SN-CONC-601', 'AVAILABLE', 600, 600, NOW(), NOW()),
                   (602, 'Keysight DSOX1204G #2', 'DSOX1204G', 'SN-CONC-602', 'AVAILABLE', 600, 600, NOW(), NOW()),
                   (603, 'Keysight DSOX1204G #3', 'DSOX1204G', 'SN-CONC-603', 'AVAILABLE', 600, 600, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """);
    }

    @AfterEach
    void tearDown() {
        reservationTransferRepository.deleteAll();
        reservationHoldRepository.deleteAll();
        reservationRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id IN (?, ?, ?)", USER_1_ID, USER_2_ID, ADMIN_ID);
        jdbcTemplate.update("DELETE FROM assets WHERE id IN (?, ?, ?)", ASSET_ID, ASSET_ID_2, ASSET_ID_3);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = 600");
        jdbcTemplate.update("DELETE FROM categories WHERE id = 600");
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)", USER_1_ID, USER_2_ID, ADMIN_ID);
    }

    @Test
    @DisplayName(
            "Pessimistic row locking prevents double booking: when two threads race for the exact same slot, exactly one wins")
    void twoThreadsBookSameSlotExactlyOneWins() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 11, 4, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 4, 16, 0);
        CreateReservationRequest request = new CreateReservationRequest(ASSET_ID, start, end);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = new CopyOnWriteArrayList<>();

        Runnable bookTask1 = () -> {
            setupSecurityContext(USER_1_ID);
            readyLatch.countDown();
            try {
                startLatch.await();
                reservationService.createReservation(request);
                successCount.incrementAndGet();
            } catch (ConflictException ex) {
                if ("RESERVATION_CONFLICT".equals(ex.getCode())) {
                    conflictCount.incrementAndGet();
                } else {
                    unexpectedExceptions.add(ex);
                }
            } catch (Throwable t) {
                unexpectedExceptions.add(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        Runnable bookTask2 = () -> {
            setupSecurityContext(USER_2_ID);
            readyLatch.countDown();
            try {
                startLatch.await();
                reservationService.createReservation(request);
                successCount.incrementAndGet();
            } catch (ConflictException ex) {
                if ("RESERVATION_CONFLICT".equals(ex.getCode())) {
                    conflictCount.incrementAndGet();
                } else {
                    unexpectedExceptions.add(ex);
                }
            } catch (Throwable t) {
                unexpectedExceptions.add(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        Future<?> f1 = executor.submit(bookTask1);
        Future<?> f2 = executor.submit(bookTask2);

        // Wait for both worker threads to establish SecurityContext and be ready at the gate
        assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Release both threads simultaneously into createReservation
        startLatch.countDown();

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Ensure no unexpected exceptions were silently swallowed
        assertThat(unexpectedExceptions).isEmpty();

        // Exactly one student succeeded and exactly one was rejected with RESERVATION_CONFLICT
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Verify only 1 reservation was committed to PostgreSQL
        List<Reservation> saved = reservationRepository.findAll();
        assertThat(saved).hasSize(1);
        Reservation reservation = saved.get(0);
        assertThat(reservation.getAssetId()).isEqualTo(ASSET_ID);
        assertThat(reservation.getStartTime()).isEqualTo(start);
        assertThat(reservation.getEndTime()).isEqualTo(end);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName(
            "User advisory lock prevents quota bypass: when same student with 1 active booking races on 2 different assets, exactly 1 wins")
    void sameStudentTwoDifferentAssetsCannotBypassActiveQuotaLimit() throws Exception {
        // Student 101 already has 1 active booking on ASSET_ID (601)
        setupSecurityContext(USER_1_ID);
        try {
            reservationService.createReservation(new CreateReservationRequest(
                    ASSET_ID, LocalDateTime.of(2026, 11, 4, 10, 0), LocalDateTime.of(2026, 11, 4, 12, 0)));
        } finally {
            SecurityContextHolder.clearContext();
        }

        // Now Student 101 fires 2 simultaneous requests for 2 different assets (602 and 603)
        LocalDateTime start = LocalDateTime.of(2026, 11, 4, 14, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 4, 16, 0);
        CreateReservationRequest reqAsset2 = new CreateReservationRequest(ASSET_ID_2, start, end);
        CreateReservationRequest reqAsset3 = new CreateReservationRequest(ASSET_ID_3, start, end);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger limitReachedCount = new AtomicInteger(0);
        List<Throwable> unexpectedExceptions = new CopyOnWriteArrayList<>();

        // Both threads run the same booking code; only the asset differs.
        Consumer<CreateReservationRequest> bookAs = request -> {
            setupSecurityContext(USER_1_ID);
            readyLatch.countDown();
            try {
                startLatch.await();
                reservationService.createReservation(request);
                successCount.incrementAndGet();
            } catch (ConflictException ex) {
                if ("RESERVATION_LIMIT_REACHED".equals(ex.getCode())) {
                    limitReachedCount.incrementAndGet();
                } else {
                    unexpectedExceptions.add(ex);
                }
            } catch (Throwable t) {
                unexpectedExceptions.add(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        // shutdown() is in finally so a timeout below does not leave the two threads running.
        try {
            Future<?> f1 = executor.submit(() -> bookAs.accept(reqAsset2));
            Future<?> f2 = executor.submit(() -> bookAs.accept(reqAsset3));

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpectedExceptions).isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(limitReachedCount.get()).isEqualTo(1);

        long activeCount = reservationRepository.countByUserIdAndStatusIn(USER_1_ID, ReservationStatus.ACTIVE_STATUSES);
        assertThat(activeCount).isEqualTo(2L);
    }

    @Test
    @DisplayName(
            "Consistent lock ordering prevents deadlock between concurrent desk return and student extension (#270 review)")
    void returnAndExtendConcurrentExecutionIsDeadlockFree() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 11, 4, 10, 0);
        LocalDateTime end = LocalDateTime.of(2026, 11, 4, 12, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 3, 10, 0);

        // Seed an active CHECKED_OUT reservation on ASSET_ID (valid for both return and extension)
        Reservation r = new Reservation(USER_1_ID, ASSET_ID, start, end, createdAt);
        r.checkOut(start);
        Reservation created = reservationRepository.save(r);

        Long reservationId = created.getId();
        LocalDateTime extendedEnd = end.plusHours(1);

        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Throwable> unexpectedExceptions = new CopyOnWriteArrayList<>();
        AtomicInteger returnSuccess = new AtomicInteger(0);
        AtomicInteger extendSuccess = new AtomicInteger(0);
        AtomicInteger extendRejected = new AtomicInteger(0);

        Runnable returnTask = () -> {
            setupSecurityContext(ADMIN_ID, "ADMIN");
            readyLatch.countDown();
            try {
                startLatch.await();
                reservationService.returnReservation(reservationId);
                returnSuccess.incrementAndGet();
            } catch (Throwable t) {
                unexpectedExceptions.add(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        Runnable extendTask = () -> {
            setupSecurityContext(USER_1_ID);
            readyLatch.countDown();
            try {
                startLatch.await();
                reservationService.extendReservation(reservationId, new ExtendReservationRequest(extendedEnd));
                extendSuccess.incrementAndGet();
            } catch (InvalidStateTransitionException ex) {
                extendRejected.incrementAndGet();
            } catch (Throwable t) {
                unexpectedExceptions.add(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        try {
            Future<?> f1 = executor.submit(returnTask);
            Future<?> f2 = executor.submit(extendTask);

            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();

            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpectedExceptions).isEmpty();
        assertThat(returnSuccess.get()).isEqualTo(1);
        assertThat(extendSuccess.get() + extendRejected.get()).isEqualTo(1);

        Reservation finalRes = reservationRepository.findById(reservationId).orElseThrow();
        assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.RETURNED);
    }

    @Test
    @DisplayName(
            "Disabled-user booking race (#229): createReservation waiting on lockUserQuota is rejected with 403 USER_DISABLED")
    void bookingBlockedOnQuotaLockIsRejectedWhenUserIsDisabled() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = now.withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);
        CreateReservationRequest request = new CreateReservationRequest(ASSET_ID, start, end);

        assertRejectedWithUserDisabledWhileWaitingOnQuotaLock(
                USER_1_ID, () -> reservationService.createReservation(request), "ReservationService.createReservation");

        assertThat(reservationRepository.countByUserIdAndStatusIn(USER_1_ID, ReservationStatus.ACTIVE_STATUSES))
                .as("disabled student must end with 0 active reservations after createReservation race")
                .isZero();
    }

    @Test
    @DisplayName(
            "Disabled-user booking race (#229, #310): bookBundle waiting on lockUserQuota is rejected with 403 USER_DISABLED")
    void bundleBookingBlockedOnQuotaLockIsRejectedWhenUserIsDisabled() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = now.withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        assertRejectedWithUserDisabledWhileWaitingOnQuotaLock(
                USER_1_ID,
                () -> reservationService.bookBundle(USER_1_ID, List.of(ASSET_ID, ASSET_ID_2), start, end),
                "ReservationService.bookBundle");

        assertThat(reservationRepository.countByUserIdAndStatusIn(USER_1_ID, ReservationStatus.ACTIVE_STATUSES))
                .as("disabled student must end with 0 active reservations after bookBundle race")
                .isZero();
    }

    @Test
    @DisplayName(
            "Disabled-user booking race (#229, #310): confirmHold waiting on lockUserQuota is rejected with 403 USER_DISABLED")
    void confirmHoldBlockedOnQuotaLockIsRejectedWhenUserIsDisabled() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = now.withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        ReservationHold hold = reservationHoldRepository.save(
                new ReservationHold(ASSET_ID, USER_1_ID, start, end, now.plusMinutes(5), now));

        assertRejectedWithUserDisabledWhileWaitingOnQuotaLock(
                USER_1_ID, () -> holdService.confirmHold(hold.getId()), "HoldService.confirmHold");

        assertThat(reservationRepository.countByUserIdAndStatusIn(USER_1_ID, ReservationStatus.ACTIVE_STATUSES))
                .as("disabled student must end with 0 active reservations after confirmHold race")
                .isZero();
    }

    @Test
    @DisplayName(
            "Disabled-user booking race (#229, #310): acceptTransfer waiting on lockUserQuota is rejected with 403 USER_DISABLED")
    void acceptTransferBlockedOnQuotaLockIsRejectedWhenRecipientIsDisabled() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = now.withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(2);

        Reservation senderReservation =
                reservationRepository.save(new Reservation(USER_2_ID, ASSET_ID, start, end, now.minusHours(1)));
        ReservationTransfer transfer = reservationTransferRepository.save(
                new ReservationTransfer(senderReservation.getId(), USER_2_ID, USER_1_ID, now));

        assertRejectedWithUserDisabledWhileWaitingOnQuotaLock(
                USER_1_ID, () -> transferService.acceptTransfer(transfer.getId()), "TransferService.acceptTransfer");

        Reservation afterRace =
                reservationRepository.findById(senderReservation.getId()).orElseThrow();
        assertThat(afterRace.getUserId())
                .as(
                        "reservation must stay owned by the sender when recipient is disabled while waiting on lockUserQuota; "
                                + "see TransferService.acceptTransfer")
                .isEqualTo(USER_2_ID);
    }

    @Test
    @DisplayName(
            "Disable-cancel vs extend race (#229, #322): cancelUpcomingReservationsForUser locks rows FOR UPDATE so concurrent extendReservation never rolls it back")
    void extendRunningDuringCancelUpcomingDoesNotRollBackCancel() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = now.withHour(14).withMinute(0);
        LocalDateTime end = start.plusHours(1);
        LocalDateTime extendedEnd = start.plusHours(2);

        Reservation booking = reservationRepository.save(new Reservation(USER_1_ID, ASSET_ID, start, end, now));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger cancelledCount = new AtomicInteger(-1);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        Future<?> cancelTask = executor.submit(() -> {
            setupSecurityContext(ADMIN_ID, "ADMIN");
            readyLatch.countDown();
            try {
                startLatch.await();
                cancelledCount.set(reservationService.cancelUpcomingReservationsForUser(USER_1_ID, "Account disabled"));
            } catch (Throwable t) {
                unexpected.add(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        Future<?> extendTask = executor.submit(() -> {
            setupSecurityContext(USER_1_ID);
            readyLatch.countDown();
            try {
                startLatch.await();
                reservationService.extendReservation(booking.getId(), new ExtendReservationRequest(extendedEnd));
            } catch (InvalidStateTransitionException | ConflictException expected) {
                // Expected if cancel committed first and transitioned the booking to CANCELLED.
            } catch (Throwable t) {
                unexpected.add(t);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });

        try {
            assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
            startLatch.countDown();
            cancelTask.get(10, TimeUnit.SECONDS);
            extendTask.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpected)
                .as("concurrent extendReservation must serialize on FOR UPDATE instead of tripping @Version rollback; "
                        + "see ReservationService.cancelUpcomingReservationsForUser")
                .isEmpty();
        assertThat(cancelledCount.get())
                .as("cancelUpcomingReservationsForUser must cancel the booking cleanly")
                .isEqualTo(1);
        Reservation after = reservationRepository.findById(booking.getId()).orElseThrow();
        assertThat(after.getStatus())
                .as("booking must end up CANCELLED regardless of whether extend ran before or after cancel")
                .isEqualTo(ReservationStatus.CANCELLED);
    }

    /**
     * Thread 1 acquires lockUserQuota(targetUserId) FIRST, polls PostgreSQL {@code pg_stat_activity}
     * + {@code pg_locks} until Thread 2 is genuinely blocked on the advisory lock (so any accidental
     * pre-lock User read would already have populated the Hibernate L1 cache), and ONLY THEN disables
     * the user and commits.
     */
    private void assertRejectedWithUserDisabledWhileWaitingOnQuotaLock(
            Long targetUserId, Runnable action, String ownerPath) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch quotaLockHeld = new CountDownLatch(1);
        CountDownLatch actionStarted = new CountDownLatch(1);
        AtomicBoolean actionDone = new AtomicBoolean(false);
        AtomicInteger disabledRejected = new AtomicInteger(0);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        Future<?> disableAndCancel = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
            reservationRepository.lockUserQuota(targetUserId);
            quotaLockHeld.countDown();
            try {
                actionStarted.await(5, TimeUnit.SECONDS);
                waitUntilBlockedOnUserQuotaLock(targetUserId, actionDone);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Disable ONLY after Thread 2 is blocked on lockUserQuota, reproducing the L1 cache trap if
            // the action loaded User before acquiring the lock.
            jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", targetUserId);
        }));

        Future<?> concurrentAction = executor.submit(() -> {
            setupSecurityContext(targetUserId);
            try {
                assertThat(quotaLockHeld.await(5, TimeUnit.SECONDS))
                        .as("disable transaction acquired lockUserQuota first")
                        .isTrue();
                actionStarted.countDown();
                action.run();
            } catch (ForbiddenException ex) {
                if ("USER_DISABLED".equals(ex.getCode())) {
                    disabledRejected.incrementAndGet();
                } else {
                    unexpected.add(ex);
                }
            } catch (Throwable t) {
                unexpected.add(t);
            } finally {
                actionDone.set(true);
                SecurityContextHolder.clearContext();
            }
        });

        try {
            disableAndCancel.get(10, TimeUnit.SECONDS);
            concurrentAction.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(unexpected)
                .as("no unexpected error during disabled-user race; see %s", ownerPath)
                .isEmpty();
        assertThat(disabledRejected.get())
                .as(
                        "%s waiting on lockUserQuota while the user is disabled must get 403 USER_DISABLED; "
                                + "see ReservationServiceSupport.requireActiveUserAfterLock",
                        ownerPath)
                .isEqualTo(1);
    }

    /**
     * Polls PostgreSQL's {@code pg_stat_activity} joined with {@code pg_locks} until another backend
     * is actively waiting on {@code pg_advisory_xact_lock(1, targetUserId)} (replaces blind sleep, #322).
     */
    private void waitUntilBlockedOnUserQuotaLock(Long targetUserId, AtomicBoolean actionDone)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!actionDone.get() && System.nanoTime() < deadlineNanos) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM pg_stat_activity a
                    JOIN pg_locks l ON l.pid = a.pid
                    WHERE a.datname = current_database()
                      AND a.pid <> pg_backend_pid()
                      AND a.wait_event_type = 'Lock'
                      AND a.wait_event = 'advisory'
                      AND l.locktype = 'advisory'
                      AND l.classid = 1
                      AND l.objid = ?
                      AND NOT l.granted
                    """, Integer.class, targetUserId.intValue());
            if (waiting != null && waiting > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
    }

    private void setupSecurityContext(Long userId) {
        setupSecurityContext(userId, "STUDENT");
    }

    private void setupSecurityContext(Long userId, String role) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("roles", List.of("ROLE_" + role))
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
    }
}

package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.common.error.ConflictException;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.ExtendReservationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        java.util.function.Consumer<CreateReservationRequest> bookAs = request -> {
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
            } catch (com.campus.common.error.InvalidStateTransitionException ex) {
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

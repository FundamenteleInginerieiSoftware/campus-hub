package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.common.error.ConflictException;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.web.CreateReservationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
    private static final Long USER_1_ID = 101L;
    private static final Long USER_2_ID = 102L;

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
                   (102, 'student102@campus.test', '{bcrypt}hash', 'Student 102', 'STUDENT', true, NOW())
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
            VALUES (601, 'Keysight DSOX1204G', 'DSOX1204G', 'SN-CONC-601', 'AVAILABLE', 600, 600, NOW(), NOW())
            ON CONFLICT (id) DO NOTHING
            """);
    }

    @AfterEach
    void tearDown() {
        reservationRepository.deleteAll();
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
                }
            } catch (Exception ignored) {
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
                }
            } catch (Exception ignored) {
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

    private void setupSecurityContext(Long userId) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("roles", List.of("ROLE_STUDENT"))
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
    }
}

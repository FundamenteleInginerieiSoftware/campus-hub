package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.common.error.ConflictException;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.WaitlistEntryRepository;
import com.campus.reservation.web.JoinWaitlistRequest;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@SpringBootTest
class WaitlistConcurrencyIT {

    @Autowired
    private WaitlistService waitlistService;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private Long holderId;
    private Long waiterId;
    private Long categoryId;
    private Long labRoomId;
    private Long assetId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        holderId = insertStudent("wl-conc-holder-" + tag + "@test.local");
        waiterId = insertStudent("wl-conc-waiter-" + tag + "@test.local");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class, "WLC-" + tag);
        labRoomId = jdbcTemplate.queryForObject(
                "INSERT INTO lab_rooms (code, name) VALUES (?, ?) RETURNING id",
                Long.class,
                "WLC-" + tag,
                "WLC Lab " + tag);
        assetId = jdbcTemplate.queryForObject(
                """
            INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (?, 'DS1054Z', ?, 'AVAILABLE', ?, ?, NOW(), NOW())
            RETURNING id
            """, Long.class, "WLC Scope " + tag, "SN-WLC-" + tag, categoryId, labRoomId);
    }

    private Long insertStudent(String email) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
            VALUES (?, '{bcrypt}hash', 'Waitlist Student', 'STUDENT', true, NOW())
            RETURNING id
            """, Long.class, email);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        waitlistEntryRepository.deleteAll();
        reservationRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id IN (?, ?)", holderId, waiterId);
        jdbcTemplate.update("DELETE FROM assets WHERE id = ?", assetId);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", holderId, waiterId);
    }

    @Test
    @DisplayName("10 parallel joinWaitlist calls from the same student create exactly 1 WAITING entry")
    void parallelJoinWaitlistSameStudentProducesExactlyOneWaitingRow() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        while (start.getDayOfWeek() == DayOfWeek.SATURDAY || start.getDayOfWeek() == DayOfWeek.SUNDAY) {
            start = start.plusDays(1);
        }
        LocalDateTime end = start.plusHours(2);

        reservationRepository.saveAndFlush(new Reservation(holderId, assetId, start, end, now));
        JoinWaitlistRequest req = new JoinWaitlistRequest(assetId, start, end);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                Jwt jwt = Jwt.withTokenValue("mock-token")
                        .header("alg", "none")
                        .subject(String.valueOf(waiterId))
                        .claim("roles", List.of("ROLE_STUDENT"))
                        .build();
                SecurityContext ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(new JwtAuthenticationToken(jwt));
                SecurityContextHolder.setContext(ctx);
                ready.countDown();
                try {
                    go.await();
                    waitlistService.joinWaitlist(req);
                    created.incrementAndGet();
                } catch (ConflictException ex) {
                    if ("WAITLIST_ALREADY_JOINED".equals(ex.getCode())) {
                        duplicates.incrementAndGet();
                    } else {
                        unexpected.add(ex);
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected)
                .as(
                        "parallel joinWaitlist calls must serialize cleanly on lockUserQuota; see WaitlistService.joinWaitlist")
                .isEmpty();
        assertThat(created.get()).isEqualTo(1);
        assertThat(duplicates.get()).isEqualTo(9);
        assertThat(waitlistEntryRepository.countByUserIdAndStatus(waiterId, WaitlistStatus.WAITING))
                .as("only 1 WAITING row must exist in PostgreSQL; see WaitlistService.joinWaitlist")
                .isEqualTo(1L);
    }
}

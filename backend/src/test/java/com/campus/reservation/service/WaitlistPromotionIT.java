package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.WaitlistEntryRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.JoinWaitlistRequest;
import com.campus.reservation.web.ReservationResponse;
import com.campus.reservation.web.WaitlistEntryResponse;
import java.time.Clock;
import java.time.DayOfWeek;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@SpringBootTest
class WaitlistPromotionIT {

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private WaitlistService waitlistService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private WaitlistEntryRepository waitlistEntryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private Long student1Id;
    private Long student2Id;
    private Long student3Id;
    private Long categoryId;
    private Long labRoomId;
    private Long assetId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        student1Id = insertUser("wl-s1-" + tag + "@test.local", "Student 1");
        student2Id = insertUser("wl-s2-" + tag + "@test.local", "Student 2");
        student3Id = insertUser("wl-s3-" + tag + "@test.local", "Student 3");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class, "WL-Cat-" + tag);
        labRoomId = jdbcTemplate.queryForObject(
                "INSERT INTO lab_rooms (code, name) VALUES (?, ?) RETURNING id",
                Long.class,
                "WL-" + tag,
                "WL Lab " + tag);
        assetId = jdbcTemplate.queryForObject("""
            INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (?, 'DS1054Z', ?, 'AVAILABLE', ?, ?, NOW(), NOW())
            RETURNING id
            """, Long.class, "Scope " + tag, "SN-WL-" + tag, categoryId, labRoomId);
    }

    private Long insertUser(String email, String name) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
            VALUES (?, '{bcrypt}hash', ?, 'STUDENT', true, NOW())
            RETURNING id
            """, Long.class, email, name);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        waitlistEntryRepository.deleteAll();
        reservationRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id IN (?, ?, ?)", student1Id, student2Id, student3Id);
        jdbcTemplate.update("DELETE FROM assets WHERE id = ?", assetId);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)", student1Id, student2Id, student3Id);
    }

    @Test
    @DisplayName("Cancelling a confirmed reservation promotes the first FIFO waitlist entry AFTER_COMMIT")
    void cancellingReservationPromotesFirstFifoWaitlistEntry() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        while (start.getDayOfWeek() == DayOfWeek.SATURDAY || start.getDayOfWeek() == DayOfWeek.SUNDAY) {
            start = start.plusDays(1);
        }
        LocalDateTime end = start.plusHours(2);

        setAuth(student1Id);
        ReservationResponse original =
                reservationService.createReservation(new CreateReservationRequest(assetId, start, end));

        setAuth(student2Id);
        WaitlistEntryResponse firstWaiter = waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));

        setAuth(student3Id);
        WaitlistEntryResponse secondWaiter = waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));

        setAuth(student1Id);
        reservationService.cancelReservation(original.id());

        WaitlistEntry updatedFirst =
                waitlistEntryRepository.findById(firstWaiter.id()).orElseThrow();
        WaitlistEntry updatedSecond =
                waitlistEntryRepository.findById(secondWaiter.id()).orElseThrow();

        assertThat(updatedFirst.getStatus())
                .as(
                        "first FIFO waiter must be promoted after cancel commits; see WaitlistPromotionListener.onSlotFreed")
                .isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(updatedFirst.getPromotedReservationId())
                .as("promoted waitlist entry must reference a newly created CONFIRMED reservation")
                .isNotNull();
        Reservation promotedBooking = reservationRepository
                .findById(updatedFirst.getPromotedReservationId())
                .orElseThrow();
        assertThat(promotedBooking.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(promotedBooking.getUserId()).isEqualTo(student2Id);
        assertThat(updatedSecond.getStatus())
                .as("second FIFO waiter must stay WAITING because the slot is now re-occupied")
                .isEqualTo(WaitlistStatus.WAITING);
    }

    private void setAuth(Long userId) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("roles", List.of("ROLE_STUDENT"))
                .build();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(ctx);
    }
}

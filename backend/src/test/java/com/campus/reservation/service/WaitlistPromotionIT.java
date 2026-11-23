package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.HoldStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.ReservationHold;
import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.repository.ReservationHoldRepository;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.WaitlistEntryRepository;
import com.campus.reservation.web.CreateReservationRequest;
import com.campus.reservation.web.JoinWaitlistRequest;
import com.campus.reservation.web.ReservationHoldResponse;
import com.campus.reservation.web.ReservationResponse;
import com.campus.reservation.web.WaitlistEntryResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private HoldService holdService;

    @Autowired
    private WaitlistService waitlistService;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationHoldRepository reservationHoldRepository;

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
    private final List<Long> extraAssetIds = new ArrayList<>();
    private final List<Long> extraUserIds = new ArrayList<>();

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
        List<Long> allAssets = new ArrayList<>(extraAssetIds);
        allAssets.add(assetId);
        for (Long aId : allAssets) {
            jdbcTemplate.update("DELETE FROM waitlist_entries WHERE asset_id = ?", aId);
            jdbcTemplate.update("DELETE FROM reservation_holds WHERE asset_id = ?", aId);
            jdbcTemplate.update("DELETE FROM reservations WHERE asset_id = ?", aId);
        }
        List<Long> allUsers = new ArrayList<>(extraUserIds);
        allUsers.addAll(List.of(student1Id, student2Id, student3Id));
        for (Long uId : allUsers) {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id = ?", uId);
        }
        for (Long aId : extraAssetIds) {
            jdbcTemplate.update("DELETE FROM assets WHERE id = ?", aId);
        }
        jdbcTemplate.update("DELETE FROM assets WHERE id = ?", assetId);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        for (Long uId : allUsers) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uId);
        }
        extraAssetIds.clear();
        extraUserIds.clear();
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
        assertThat(promotedBooking.getStatus())
                .as("promoted reservation must be CONFIRMED; see WaitlistService.processPromotionsForAsset")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(promotedBooking.getUserId())
                .as(
                        "promoted reservation must belong to the first FIFO waiter; see WaitlistService.processPromotionsForAsset")
                .isEqualTo(student2Id);
        assertThat(updatedSecond.getStatus())
                .as("second FIFO waiter must stay WAITING because the slot is now re-occupied")
                .isEqualTo(WaitlistStatus.WAITING);
    }

    @Test
    @DisplayName("Releasing a slot hold promotes the first FIFO waitlist entry AFTER_COMMIT")
    void releasingHoldPromotesFirstFifoWaitlistEntry() {
        LocalDateTime start = nextWeekdaySlot(14);
        LocalDateTime end = start.plusHours(2);

        setAuth(student1Id);
        ReservationHoldResponse hold =
                holdService.createHold(new com.campus.reservation.web.CreateHoldRequest(assetId, start, end));

        setAuth(student2Id);
        WaitlistEntryResponse waiter = waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));

        setAuth(student1Id);
        holdService.releaseHold(hold.id());

        WaitlistEntry updated = waitlistEntryRepository.findById(waiter.id()).orElseThrow();
        assertThat(updated.getStatus())
                .as(
                        "releasing a hold must publish SlotFreedEvent and promote the waiting student; see HoldService.releaseHold")
                .isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(updated.getPromotedReservationId())
                .as("promoted waiter after hold release must have a reservation ID")
                .isNotNull();
    }

    @Test
    @DisplayName("Expiring an abandoned hold via sweepExpiredHolds promotes the waiting student AFTER_COMMIT")
    void sweepExpiredHoldsPromotesWaitingStudent() {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = nextWeekdaySlot(10);
        LocalDateTime end = start.plusHours(2);

        ReservationHold hold = new ReservationHold(assetId, student1Id, start, end, now.plusMinutes(1), now);
        hold = reservationHoldRepository.save(hold);

        setAuth(student2Id);
        WaitlistEntryResponse waiter = waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));

        jdbcTemplate.update(
                "UPDATE reservation_holds SET expires_at = ? WHERE id = ?", now.minusSeconds(5), hold.getId());

        int swept = holdService.sweepExpiredHolds(now);

        assertThat(swept)
                .as("expired hold must be swept; see HoldService.sweepExpiredHolds")
                .isEqualTo(1);
        WaitlistEntry updated = waitlistEntryRepository.findById(waiter.id()).orElseThrow();
        assertThat(updated.getStatus())
                .as(
                        "hold expiry sweep must publish SlotFreedEvent and promote waiter; see HoldService.sweepExpiredHolds")
                .isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(reservationHoldRepository
                        .findById(hold.getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(HoldStatus.EXPIRED);
    }

    @Test
    @DisplayName("Promotion skips a disabled waiter (EXPIRED) and promotes the next active FIFO waiter")
    void promotionSkipsDisabledWaiterAndPromotesNext() {
        LocalDateTime start = nextWeekdaySlot(12);
        LocalDateTime end = start.plusHours(2);

        setAuth(student1Id);
        ReservationResponse original =
                reservationService.createReservation(new CreateReservationRequest(assetId, start, end));

        setAuth(student2Id);
        WaitlistEntryResponse disabledWaiter =
                waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));

        setAuth(student3Id);
        WaitlistEntryResponse activeWaiter = waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));

        jdbcTemplate.update("UPDATE users SET enabled = false WHERE id = ?", student2Id);

        setAuth(student1Id);
        reservationService.cancelReservation(original.id());

        WaitlistEntry updatedDisabled =
                waitlistEntryRepository.findById(disabledWaiter.id()).orElseThrow();
        WaitlistEntry updatedActive =
                waitlistEntryRepository.findById(activeWaiter.id()).orElseThrow();

        assertThat(updatedDisabled.getStatus())
                .as(
                        "disabled waiter must be marked EXPIRED during promotion; see WaitlistService.processNextPromotionStep")
                .isEqualTo(WaitlistStatus.EXPIRED);
        assertThat(updatedActive.getStatus())
                .as("next active FIFO waiter must be promoted after disabled waiter is skipped")
                .isEqualTo(WaitlistStatus.PROMOTED);
    }

    @Test
    @DisplayName("Concurrent promotions across two assets with opposite user orders never deadlock (#350)")
    void concurrentPromotionsAcrossTwoAssetsNeverDeadlock() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long asset2Id =
                jdbcTemplate.queryForObject("""
            INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (?, 'DS1054Z', ?, 'AVAILABLE', ?, ?, NOW(), NOW())
            RETURNING id
            """, Long.class, "Scope2 " + tag, "SN-WL2-" + tag, categoryId, labRoomId);
        extraAssetIds.add(asset2Id);

        Long blocker2Id = insertUser("wl-b2-" + tag + "@test.local", "Blocker 2");
        extraUserIds.add(blocker2Id);

        LocalDateTime slot1Start = nextWeekdaySlot(10);
        LocalDateTime slot1End = slot1Start.plusHours(2);
        LocalDateTime slot2Start = nextWeekdaySlot(14);
        LocalDateTime slot2End = slot2Start.plusHours(2);

        // Occupy two disjoint slots on each asset
        setAuth(student3Id);
        ReservationResponse rA1 =
                reservationService.createReservation(new CreateReservationRequest(assetId, slot1Start, slot1End));
        ReservationResponse rA2 =
                reservationService.createReservation(new CreateReservationRequest(assetId, slot2Start, slot2End));

        setAuth(blocker2Id);
        ReservationResponse rB1 =
                reservationService.createReservation(new CreateReservationRequest(asset2Id, slot1Start, slot1End));
        ReservationResponse rB2 =
                reservationService.createReservation(new CreateReservationRequest(asset2Id, slot2Start, slot2End));

        // Asset 1 FIFO: student1 then student2; Asset 2 FIFO: student2 then student1
        setAuth(student1Id);
        WaitlistEntryResponse wA1 =
                waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, slot1Start, slot1End));
        setAuth(student2Id);
        WaitlistEntryResponse wA2 =
                waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, slot2Start, slot2End));

        setAuth(student2Id);
        WaitlistEntryResponse wB1 =
                waitlistService.joinWaitlist(new JoinWaitlistRequest(asset2Id, slot1Start, slot1End));
        setAuth(student1Id);
        WaitlistEntryResponse wB2 =
                waitlistService.joinWaitlist(new JoinWaitlistRequest(asset2Id, slot2Start, slot2End));

        // Free all 4 slots in DB directly, then race processPromotionsForAsset on both assets behind a barrier
        jdbcTemplate.update(
                "UPDATE reservations SET status = 'CANCELLED' WHERE id IN (?, ?, ?, ?)",
                rA1.id(),
                rA2.id(),
                rB1.id(),
                rB2.id());

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return waitlistService.processPromotionsForAsset(assetId);
            });
            Future<Integer> f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return waitlistService.processPromotionsForAsset(asset2Id);
            });
            assertThat(f1.get(15, TimeUnit.SECONDS))
                    .as("asset 1 must promote both waiters without deadlocking")
                    .isEqualTo(2);
            assertThat(f2.get(15, TimeUnit.SECONDS))
                    .as("asset 2 must promote both waiters without deadlocking")
                    .isEqualTo(2);
        }

        for (Long entryId : List.of(wA1.id(), wA2.id(), wB1.id(), wB2.id())) {
            assertThat(waitlistEntryRepository.findById(entryId).orElseThrow().getStatus())
                    .as("waitlist entry #" + entryId + " must be PROMOTED cleanly without deadlock rollback")
                    .isEqualTo(WaitlistStatus.PROMOTED);
        }
    }

    private LocalDateTime nextWeekdaySlot(int hour) {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime slot =
                now.plusDays(1).withHour(hour).withMinute(0).withSecond(0).withNano(0);
        while (slot.getDayOfWeek() == DayOfWeek.SATURDAY || slot.getDayOfWeek() == DayOfWeek.SUNDAY) {
            slot = slot.plusDays(1);
        }
        return slot;
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

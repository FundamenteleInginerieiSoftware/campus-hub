package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.catalog.api.CatalogApi;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    private ReservationLifecycleService reservationLifecycleService;

    @Autowired
    private CatalogApi catalogApi;

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
        return insertUser(email, name, "STUDENT");
    }

    private Long insertUser(String email, String name, String role) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
            VALUES (?, '{bcrypt}hash', ?, ?, true, NOW())
            RETURNING id
            """, Long.class, email, name, role);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        List<Long> allAssets = new ArrayList<>(extraAssetIds);
        allAssets.add(assetId);
        List<Long> allUsers = new ArrayList<>(extraUserIds);
        allUsers.addAll(List.of(student1Id, student2Id, student3Id));

        String assetPlaceholders =
                String.join(",", allAssets.stream().map(id -> "?").toList());
        String userPlaceholders =
                String.join(",", allUsers.stream().map(id -> "?").toList());
        List<Object> auditParams = new ArrayList<>();
        auditParams.addAll(allUsers);
        auditParams.addAll(allAssets);
        auditParams.addAll(allUsers);
        auditParams.addAll(allAssets);
        auditParams.addAll(allUsers);
        jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE actor_id IN (" + userPlaceholders + ") "
                        + "OR (entity_type = 'WaitlistEntry' AND entity_id IN ("
                        + "SELECT id FROM waitlist_entries WHERE asset_id IN (" + assetPlaceholders
                        + ") OR user_id IN (" + userPlaceholders + "))) "
                        + "OR (entity_type = 'Reservation' AND entity_id IN ("
                        + "SELECT id FROM reservations WHERE asset_id IN (" + assetPlaceholders
                        + ") OR user_id IN (" + userPlaceholders + ")))",
                auditParams.toArray());

        for (Long aId : allAssets) {
            jdbcTemplate.update("DELETE FROM waitlist_entries WHERE asset_id = ?", aId);
            jdbcTemplate.update("DELETE FROM reservation_holds WHERE asset_id = ?", aId);
            jdbcTemplate.update("DELETE FROM reservations WHERE asset_id = ?", aId);
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

    @Test
    @DisplayName("Returning an OVERDUE reservation past endTime promotes a student waiting on a future slot (#350)")
    void returningOverdueReservationPromotesWaitingStudent() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long adminId = insertUser("wl-admin-" + tag + "@test.local", "Desk Admin", "ADMIN");
        extraUserIds.add(adminId);

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Reservation overdue =
                new Reservation(student1Id, assetId, now.minusHours(3), now.minusHours(1), now.minusHours(4));
        overdue.checkOut(now.minusHours(3).plusMinutes(5));
        overdue.markOverdue();
        overdue = reservationRepository.saveAndFlush(overdue);

        LocalDateTime futureStart = nextWeekdaySlot(14);
        LocalDateTime futureEnd = futureStart.plusHours(2);

        setAuth(student2Id);
        WaitlistEntryResponse waiter =
                waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, futureStart, futureEnd));

        setAuth(adminId, "ROLE_ADMIN");
        reservationService.returnReservation(overdue.getId());

        WaitlistEntry updated = waitlistEntryRepository.findById(waiter.id()).orElseThrow();
        assertThat(updated.getStatus())
                .as("returning an OVERDUE reservation must publish SlotFreedEvent and promote the waiter; "
                        + "see ReservationService.returnReservation")
                .isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(updated.getPromotedReservationId())
                .as("promoted waiter after overdue return must reference a CONFIRMED reservation; "
                        + "see WaitlistService.processPromotionsForAsset")
                .isNotNull();
        Reservation promotedBooking = reservationRepository
                .findById(updated.getPromotedReservationId())
                .orElseThrow();
        assertThat(promotedBooking.getStatus())
                .as("promoted reservation after overdue return must be CONFIRMED")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(promotedBooking.getUserId())
                .as("promoted reservation after overdue return must belong to student2")
                .isEqualTo(student2Id);
    }

    @Test
    @DisplayName(
            "Repaired asset transitioning back to AVAILABLE promotes WAITING entries on the next lifecycle sweep (#350)")
    void repairedAssetPromotesWaitingStudentDuringLifecycleSweep() {
        LocalDateTime futureStart = nextWeekdaySlot(10);
        LocalDateTime futureEnd = futureStart.plusHours(2);

        setAuth(student1Id);
        ReservationResponse original =
                reservationService.createReservation(new CreateReservationRequest(assetId, futureStart, futureEnd));

        setAuth(student2Id);
        WaitlistEntryResponse waiter =
                waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, futureStart, futureEnd));

        // Simulate incident damage: asset goes UNDER_MAINTENANCE and its booking is force-cancelled.
        catalogApi.markUnderMaintenance(assetId);
        reservationService.forceCancel(original.id(), assetId, "Damaged");

        WaitlistEntry whileUnderMaintenance =
                waitlistEntryRepository.findById(waiter.id()).orElseThrow();
        assertThat(whileUnderMaintenance.getStatus())
                .as("waiter must stay WAITING while asset is UNDER_MAINTENANCE; "
                        + "see WaitlistService.processPromotionsForAsset")
                .isEqualTo(WaitlistStatus.WAITING);

        // Simulate incident resolution: asset becomes AVAILABLE again, then lifecycle sweep runs.
        catalogApi.markAvailable(assetId);
        reservationLifecycleService.runLifecycleSweep();

        WaitlistEntry afterSweep = waitlistEntryRepository.findById(waiter.id()).orElseThrow();
        assertThat(afterSweep.getStatus())
                .as("lifecycle sweep must promote WAITING entries once repaired asset is AVAILABLE; "
                        + "see ReservationLifecycleService.runLifecycleSweep")
                .isEqualTo(WaitlistStatus.PROMOTED);
        Reservation promotedBooking = reservationRepository
                .findById(afterSweep.getPromotedReservationId())
                .orElseThrow();
        assertThat(promotedBooking.getStatus())
                .as("promoted reservation after repair sweep must be CONFIRMED")
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(promotedBooking.getUserId())
                .as("promoted reservation after repair sweep must belong to student2")
                .isEqualTo(student2Id);
    }

    @Test
    @DisplayName("Lifecycle sweep expires WAITING entries on a RETIRED asset so future sweeps stop locking it (#350)")
    void retiredAssetExpiresWaitingEntriesDuringLifecycleSweep() {
        LocalDateTime start = nextWeekdaySlot(14);
        LocalDateTime end = start.plusHours(2);

        setAuth(student1Id);
        reservationService.createReservation(new CreateReservationRequest(assetId, start, end));

        setAuth(student2Id);
        WaitlistEntryResponse joined = waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));
        SecurityContextHolder.clearContext();

        jdbcTemplate.update("UPDATE assets SET status = 'RETIRED' WHERE id = ?", assetId);

        reservationLifecycleService.runLifecycleSweep();

        WaitlistEntry afterSweep = waitlistEntryRepository.findById(joined.id()).orElseThrow();
        assertThat(afterSweep.getStatus())
                .as("WAITING entry on a permanently RETIRED asset must expire on lifecycle sweep; "
                        + "see WaitlistService.processPromotionsForAsset")
                .isEqualTo(WaitlistStatus.EXPIRED);
        assertThat(waitlistService.findAssetIdsWithWaitingEntries())
                .as("retired asset must no longer appear in findAssetIdsWithWaitingEntries after expiring; "
                        + "see ReservationLifecycleService.promoteWaitingAssets")
                .doesNotContain(assetId);
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
        setAuth(userId, "ROLE_STUDENT");
    }

    private void setAuth(Long userId, String role) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("roles", List.of(role))
                .build();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role))));
        SecurityContextHolder.setContext(ctx);
    }
}

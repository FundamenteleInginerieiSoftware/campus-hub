package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.campus.common.error.ConflictException;
import com.campus.reservation.api.ReservationStatus;
import com.campus.reservation.domain.Reservation;
import com.campus.reservation.domain.WaitlistEntry;
import com.campus.reservation.domain.WaitlistStatus;
import com.campus.reservation.repository.ReservationRepository;
import com.campus.reservation.repository.WaitlistEntryRepository;
import com.campus.reservation.web.JoinWaitlistRequest;
import com.campus.reservation.web.WaitlistEntryResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
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
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    private Long holderId;
    private Long waiterId;
    private Long adminId;
    private Long categoryId;
    private Long labRoomId;
    private Long assetId;
    private final List<Long> extraUserIds = new ArrayList<>();
    private final List<Long> extraAssetIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        holderId = insertUser("wl-conc-holder-" + tag + "@test.local", "STUDENT");
        waiterId = insertUser("wl-conc-waiter-" + tag + "@test.local", "STUDENT");
        adminId = insertUser("wl-conc-admin-" + tag + "@test.local", "ADMIN");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class, "WLC-" + tag);
        labRoomId = jdbcTemplate.queryForObject(
                "INSERT INTO lab_rooms (code, name) VALUES (?, ?) RETURNING id",
                Long.class,
                "WLC-" + tag,
                "WLC Lab " + tag);
        assetId = insertAsset("WLC Scope " + tag, "SN-WLC-" + tag);
    }

    private Long insertUser(String email, String role) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
            VALUES (?, '{bcrypt}hash', 'Waitlist User', ?, true, NOW())
            RETURNING id
            """, Long.class, email, role);
    }

    private Long insertAsset(String name, String serialNumber) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
            VALUES (?, 'DS1054Z', ?, 'AVAILABLE', ?, ?, NOW(), NOW())
            RETURNING id
            """, Long.class, name, serialNumber, categoryId, labRoomId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        List<Long> allUsers = new ArrayList<>();
        if (holderId != null) allUsers.add(holderId);
        if (waiterId != null) allUsers.add(waiterId);
        if (adminId != null) allUsers.add(adminId);
        allUsers.addAll(extraUserIds);

        List<Long> allAssets = new ArrayList<>();
        if (assetId != null) allAssets.add(assetId);
        allAssets.addAll(extraAssetIds);

        if (!allUsers.isEmpty() && !allAssets.isEmpty()) {
            String userPlaceholders =
                    String.join(",", allUsers.stream().map(id -> "?").toList());
            String assetPlaceholders =
                    String.join(",", allAssets.stream().map(id -> "?").toList());
            List<Object> params = new ArrayList<>();
            params.addAll(allUsers);
            params.addAll(allAssets);
            params.addAll(allUsers);
            params.addAll(allAssets);
            params.addAll(allUsers);
            params.addAll(allUsers);

            jdbcTemplate.update(
                    "DELETE FROM audit_logs WHERE actor_id IN (" + userPlaceholders + ") "
                            + "OR (entity_type = 'WaitlistEntry' AND entity_id IN ("
                            + "SELECT id FROM waitlist_entries WHERE asset_id IN (" + assetPlaceholders
                            + ") OR user_id IN (" + userPlaceholders + "))) "
                            + "OR (entity_type = 'Reservation' AND entity_id IN ("
                            + "SELECT id FROM reservations WHERE asset_id IN (" + assetPlaceholders
                            + ") OR user_id IN (" + userPlaceholders + "))) "
                            + "OR (entity_type = 'User' AND entity_id IN (" + userPlaceholders + "))",
                    params.toArray());
        }
        for (Long aId : allAssets) {
            jdbcTemplate.update("DELETE FROM waitlist_entries WHERE asset_id = ?", aId);
        }
        for (Long uId : allUsers) {
            jdbcTemplate.update("DELETE FROM waitlist_entries WHERE user_id = ?", uId);
        }
        for (Long aId : allAssets) {
            jdbcTemplate.update("DELETE FROM reservations WHERE asset_id = ?", aId);
        }
        for (Long uId : allUsers) {
            jdbcTemplate.update("DELETE FROM reservations WHERE user_id = ?", uId);
        }
        for (Long aId : allAssets) {
            jdbcTemplate.update("DELETE FROM assets WHERE id = ?", aId);
        }
        if (labRoomId != null) {
            jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
        }
        if (categoryId != null) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
        for (Long uId : allUsers) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uId);
        }
        extraAssetIds.clear();
        extraUserIds.clear();
    }

    @Test
    @DisplayName("10 parallel joinWaitlist calls from the same student create exactly 1 WAITING entry")
    void parallelJoinWaitlistSameStudentProducesExactlyOneWaitingRow() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = nextWeekdayAt14(now);
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

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    setSecurityContext(waiterId, "ROLE_STUDENT");
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

            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("all 10 worker threads must become ready; see WaitlistConcurrencyIT")
                    .isTrue();
            go.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS))
                    .as("parallel joinWaitlist workers must finish within 15s; see WaitlistService.joinWaitlist")
                    .isTrue();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(unexpected)
                .as(
                        "parallel joinWaitlist calls must serialize cleanly on lockUserQuota; see WaitlistService.joinWaitlist")
                .isEmpty();
        assertThat(created.get())
                .as("exactly 1 parallel joinWaitlist call must succeed; see WaitlistService.joinWaitlist")
                .isEqualTo(1);
        assertThat(duplicates.get())
                .as("remaining 9 parallel joinWaitlist calls must fail with WAITLIST_ALREADY_JOINED; "
                        + "see WaitlistService.joinWaitlist")
                .isEqualTo(9);
        assertThat(waitlistEntryRepository.countByUserIdAndStatus(waiterId, WaitlistStatus.WAITING))
                .as("only 1 WAITING row must exist in PostgreSQL; see WaitlistService.joinWaitlist")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("10 parallel joinWaitlist calls across distinct assets enforce the 5 active WAITING cap")
    void parallelJoinWaitlistAcrossTenSlotsEnforcesFiveActiveCap() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = nextWeekdayAt14(now);
        LocalDateTime end = start.plusHours(2);

        List<Long> slotAssetIds = new ArrayList<>();
        slotAssetIds.add(assetId);
        String tag = UUID.randomUUID().toString().substring(0, 8);
        for (int i = 1; i < 10; i++) {
            Long extra = insertAsset("WLC Cap Scope " + tag + "-" + i, "SN-WLC-CAP-" + tag + "-" + i);
            extraAssetIds.add(extra);
            slotAssetIds.add(extra);
        }
        for (Long targetAssetId : slotAssetIds) {
            reservationRepository.saveAndFlush(new Reservation(holderId, targetAssetId, start, end, now));
        }

        int threads = slotAssetIds.size();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger limitReached = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        try {
            for (Long targetAssetId : slotAssetIds) {
                executor.submit(() -> {
                    setSecurityContext(waiterId, "ROLE_STUDENT");
                    ready.countDown();
                    try {
                        go.await();
                        waitlistService.joinWaitlist(new JoinWaitlistRequest(targetAssetId, start, end));
                        created.incrementAndGet();
                    } catch (ConflictException ex) {
                        if ("WAITLIST_LIMIT_REACHED".equals(ex.getCode())) {
                            limitReached.incrementAndGet();
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

            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("all 10 cap worker threads must become ready; see WaitlistConcurrencyIT")
                    .isTrue();
            go.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS))
                    .as("parallel cap workers must finish within 15s; see WaitlistService.joinWaitlist")
                    .isTrue();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(unexpected)
                .as("distinct-asset joinWaitlist calls must serialize cleanly on lockUserQuota without deadlocks; "
                        + "see WaitlistService.joinWaitlist")
                .isEmpty();
        assertThat(created.get())
                .as("only MAX_WAITING_ENTRIES (5) parallel joins may succeed; see WaitlistService.joinWaitlist")
                .isEqualTo(WaitlistService.MAX_WAITING_ENTRIES);
        assertThat(limitReached.get())
                .as("remaining 5 parallel joins must fail with WAITLIST_LIMIT_REACHED; "
                        + "see WaitlistService.joinWaitlist")
                .isEqualTo(5);
        assertThat(waitlistEntryRepository.countByUserIdAndStatus(waiterId, WaitlistStatus.WAITING))
                .as("PostgreSQL must contain exactly 5 WAITING rows for the student; "
                        + "see WaitlistService.joinWaitlist")
                .isEqualTo(5L);
    }

    @Test
    @DisplayName("Disabling a user cancels both active WAITING entries and upcoming CONFIRMED reservations")
    void disablingUserCancelsWaitingEntriesAndUpcomingReservations() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = nextWeekdayAt14(now);
        LocalDateTime end = start.plusHours(2);

        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long asset2Id = insertAsset("WLC Dis Scope " + tag, "SN-WLC-DIS-" + tag);
        extraAssetIds.add(asset2Id);

        reservationRepository.saveAndFlush(new Reservation(holderId, assetId, start, end, now));
        Reservation upcoming = reservationRepository.saveAndFlush(new Reservation(waiterId, asset2Id, start, end, now));

        setSecurityContext(waiterId, "ROLE_STUDENT");
        WaitlistEntryResponse joined = waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));
        SecurityContextHolder.clearContext();

        mockMvc.perform(post("/api/v1/admin/users/{id}/disable", waiterId)
                        .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        Reservation reloadedBooking =
                reservationRepository.findById(upcoming.getId()).orElseThrow();
        WaitlistEntry reloadedEntry =
                waitlistEntryRepository.findById(joined.id()).orElseThrow();

        assertThat(reloadedBooking.getStatus())
                .as("disabling user must cancel upcoming CONFIRMED reservations; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reloadedEntry.getStatus())
                .as("disabling user must cancel active WAITING entries; "
                        + "see WaitlistService.cancelWaitingEntriesForUser")
                .isEqualTo(WaitlistStatus.CANCELLED);
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'CANCEL_WAITLIST' AND entity_id = ? AND actor_id = ?",
                Integer.class,
                joined.id(),
                adminId);
        assertThat(auditCount)
                .as("CANCEL_WAITLIST audit entry must record the disabling admin as actor; "
                        + "see WaitlistService.cancelWaitingEntriesForUser")
                .isEqualTo(1);
    }

    @Test
    @DisplayName(
            "Concurrent promotion holding waitlist_entries FOR UPDATE and account disable never deadlock (REQUIRES_NEW split)")
    void concurrentPromotionHoldingWaitlistRowsAndAccountDisableNeverDeadlock() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = nextWeekdayAt14(now);
        LocalDateTime end = start.plusHours(2);

        String tag = UUID.randomUUID().toString().substring(0, 8);
        Long asset2Id = insertAsset("WLC Race Scope " + tag, "SN-WLC-RACE-" + tag);
        extraAssetIds.add(asset2Id);
        Long otherWaiterId = insertUser("wl-conc-other-" + tag + "@test.local", "STUDENT");
        extraUserIds.add(otherWaiterId);

        Reservation holderBooking =
                reservationRepository.saveAndFlush(new Reservation(holderId, assetId, start, end, now));
        Reservation upcomingOnAsset2 =
                reservationRepository.saveAndFlush(new Reservation(waiterId, asset2Id, start, end, now));

        setSecurityContext(waiterId, "ROLE_STUDENT");
        WaitlistEntryResponse waiterEntry = waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));
        setSecurityContext(otherWaiterId, "ROLE_STUDENT");
        WaitlistEntryResponse otherWaiterEntry =
                waitlistService.joinWaitlist(new JoinWaitlistRequest(assetId, start, end));
        SecurityContextHolder.clearContext();

        // Free the slot via SQL so promotion is ready to run without publishing an automatic listener event.
        jdbcTemplate.update(
                "UPDATE reservations SET status = 'CANCELLED', version = version + 1 WHERE id = ?",
                holderBooking.getId());

        CountDownLatch quotaHeld = new CountDownLatch(1);
        CountDownLatch releaseQuota = new CountDownLatch(1);
        AtomicBoolean promoDone = new AtomicBoolean(false);
        AtomicBoolean disableDone = new AtomicBoolean(false);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> quotaBlocker = executor.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        reservationRepository.lockUserQuota(waiterId);
                        quotaHeld.countDown();
                        try {
                            releaseQuota.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });

            assertThat(quotaHeld.await(5, TimeUnit.SECONDS))
                    .as("quota blocker thread must acquire lockUserQuota(waiterId) first")
                    .isTrue();

            Future<?> promoThread = executor.submit(() -> {
                try {
                    waitlistService.processPromotionsForAsset(assetId);
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    promoDone.set(true);
                }
            });

            assertThat(waitUntilBlockedOnUserQuotaLock(waiterId, 1, promoDone))
                    .as("promotion thread must hold waitlist_entries FOR UPDATE and block on lockUserQuota(waiterId); "
                            + "see WaitlistService.processPromotionsForAsset")
                    .isTrue();

            Future<?> disableThread = executor.submit(() -> {
                try {
                    mockMvc.perform(post("/api/v1/admin/users/{id}/disable", waiterId)
                                    .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                            .andExpect(status().isOk());
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    disableDone.set(true);
                }
            });

            assertThat(waitUntilBlockedOnUserQuotaLock(waiterId, 2, disableDone))
                    .as(
                            "disable listener Step 1 must wait on lockUserQuota(waiterId) before Step 2 scans waitlist_entries; "
                                    + "see UserDisabledReservationListener.onUserDisabled")
                    .isTrue();

            releaseQuota.countDown();
            quotaBlocker.get(10, TimeUnit.SECONDS);
            promoThread.get(10, TimeUnit.SECONDS);
            disableThread.get(10, TimeUnit.SECONDS);
        } finally {
            releaseQuota.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(unexpected)
                .as("concurrent waitlist promotion and account disable must never deadlock; "
                        + "see WaitlistService.cancelWaitingEntriesForUser and UserDisabledReservationListener")
                .isEmpty();

        Boolean enabled =
                jdbcTemplate.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, waiterId);
        Reservation reloadedUpcoming =
                reservationRepository.findById(upcomingOnAsset2.getId()).orElseThrow();
        WaitlistEntry reloadedWaiter =
                waitlistEntryRepository.findById(waiterEntry.id()).orElseThrow();
        WaitlistEntry reloadedOther =
                waitlistEntryRepository.findById(otherWaiterEntry.id()).orElseThrow();

        assertThat(enabled)
                .as("target user must end disabled; see AdminUserService.disable")
                .isFalse();
        assertThat(reloadedUpcoming.getStatus())
                .as("disabled user's upcoming booking must still be CANCELLED even during concurrent promotion; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isEqualTo(ReservationStatus.CANCELLED);
        assertThat(reloadedWaiter.getStatus())
                .as("disabled user's waitlist entry must end CANCELLED or EXPIRED, never PROMOTED; "
                        + "see WaitlistService.cancelWaitingEntriesForUser")
                .isIn(WaitlistStatus.CANCELLED, WaitlistStatus.EXPIRED);
        assertThat(reloadedOther.getStatus())
                .as("second eligible FIFO waiter must still be PROMOTED without deadlock; "
                        + "see WaitlistService.processPromotionsForAsset")
                .isEqualTo(WaitlistStatus.PROMOTED);
    }

    @Test
    @DisplayName(
            "Disable listener drains in-flight joinWaitlist holding lockUserQuota so zero orphan WAITING rows remain (#349)")
    void disablingUserDrainsInFlightJoinWaitlistLeavingZeroOrphanWaitingRows() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime start = nextWeekdayAt14(now);
        LocalDateTime end = start.plusHours(2);

        reservationRepository.saveAndFlush(new Reservation(holderId, assetId, start, end, now));

        CountDownLatch inFlightJoinPaused = new CountDownLatch(1);
        CountDownLatch releaseInFlightJoin = new CountDownLatch(1);
        AtomicBoolean disableDone = new AtomicBoolean(false);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        List<Long> createdEntryIds = new CopyOnWriteArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Simulate an in-flight joinWaitlist that already acquired lockUserQuota(waiterId) and
            // passed requireActiveUserAfterLock right before the admin disabled the user, pausing before commit.
            Future<?> inFlightJoin = executor.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        reservationRepository.lockUserQuota(waiterId);
                        WaitlistEntry saved = waitlistEntryRepository.saveAndFlush(
                                new WaitlistEntry(assetId, waiterId, start, end, now));
                        createdEntryIds.add(saved.getId());
                        inFlightJoinPaused.countDown();
                        try {
                            releaseInFlightJoin.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            });

            assertThat(inFlightJoinPaused.await(5, TimeUnit.SECONDS))
                    .as("in-flight joinWaitlist simulation must hold lockUserQuota before commit")
                    .isTrue();

            Future<?> disableThread = executor.submit(() -> {
                try {
                    mockMvc.perform(post("/api/v1/admin/users/{id}/disable", waiterId)
                                    .with(jwt().jwt(j -> j.subject(String.valueOf(adminId)))
                                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                            .andExpect(status().isOk());
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    disableDone.set(true);
                }
            });

            assertThat(waitUntilBlockedOnUserQuotaLock(waiterId, 1, disableDone))
                    .as("disable listener Step 1 must block on lockUserQuota(waiterId) until in-flight join commits; "
                            + "see UserDisabledReservationListener.onUserDisabled")
                    .isTrue();

            releaseInFlightJoin.countDown();
            inFlightJoin.get(10, TimeUnit.SECONDS);
            disableThread.get(10, TimeUnit.SECONDS);
        } finally {
            releaseInFlightJoin.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(unexpected)
                .as("disable draining in-flight joinWaitlist must complete without errors; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isEmpty();
        assertThat(createdEntryIds).hasSize(1);
        WaitlistEntry reloaded =
                waitlistEntryRepository.findById(createdEntryIds.getFirst()).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("Step 2 must cancel the WAITING entry committed by the drained in-flight joinWaitlist; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isEqualTo(WaitlistStatus.CANCELLED);
        assertThat(waitlistEntryRepository.countByUserIdAndStatus(waiterId, WaitlistStatus.WAITING))
                .as("disabled user must have zero orphan WAITING rows after listener finishes; "
                        + "see UserDisabledReservationListener.onUserDisabled")
                .isZero();
    }

    private boolean waitUntilBlockedOnUserQuotaLock(Long targetUserId, int minWaiters, AtomicBoolean actionDone)
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
            if (waiting != null && waiting >= minWaiters) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return false;
    }

    private LocalDateTime nextWeekdayAt14(LocalDateTime now) {
        LocalDateTime start =
                now.plusDays(1).withHour(14).withMinute(0).withSecond(0).withNano(0);
        while (start.getDayOfWeek() == DayOfWeek.SATURDAY || start.getDayOfWeek() == DayOfWeek.SUNDAY) {
            start = start.plusDays(1);
        }
        return start;
    }

    private void setSecurityContext(Long userId, String role) {
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

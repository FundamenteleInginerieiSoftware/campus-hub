package com.campus.user.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.common.error.BusinessRuleException;
import com.campus.user.api.Role;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #269 point 1 (Matei's read-through): the "at least one enabled admin" rule when the only two admins act on
 * each other at the same moment. Each request counts the enabled admins and sees 2, so each thinks it may
 * remove the other one. {@code AdminUserRepository.lockEnabledByRole} (in {@code disable} and in
 * {@code changeRole}) makes the second request wait until the first commits; it then counts 1 and gets 409
 * LAST_ADMIN_REQUIRED. Without the lock both pass and the app is left with no admin at all.
 *
 * <p>Not @Transactional, so each thread really commits (like {@code IncidentQuotaConcurrencyIT}). The same
 * commit gate as in {@code IncidentResolveConcurrencyIT} makes the race happen every time: without the lock
 * both requests always overlap, with it the test passes in about 3 seconds.
 *
 * <p>The rule only bites when exactly these two admins are enabled. Any other enabled admin left in
 * campus_test is switched off in setUp and switched back on in tearDown.
 */
@SpringBootTest
class AdminUserConcurrencyIT {

    private static final long COMMIT_GATE_SECONDS = 3;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long firstAdminId;
    private Long secondAdminId;
    private List<Long> otherEnabledAdminIds = List.of();

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        firstAdminId = insertAdmin("admin1-" + tag + "@uadmrace.test");
        secondAdminId = insertAdmin("admin2-" + tag + "@uadmrace.test");
        otherEnabledAdminIds = jdbcTemplate.queryForList(
                "UPDATE users SET enabled = false WHERE role = 'ADMIN' AND enabled = true AND id NOT IN (?, ?) RETURNING id",
                Long.class,
                firstAdminId,
                secondAdminId);
    }

    @AfterEach
    void tearDown() {
        otherEnabledAdminIds.forEach(id -> jdbcTemplate.update("UPDATE users SET enabled = true WHERE id = ?", id));
        jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id IN (?, ?)", firstAdminId, secondAdminId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", firstAdminId, secondAdminId);
    }

    @Test
    @DisplayName("Admin lock: the only two admins make each other STUDENT at once, exactly one admin is left")
    void twoAdminsDemotingEachOther_leaveExactlyOneAdmin() throws Exception {
        Outcome outcome = runAtTheSameMoment(
                () -> adminUserService.changeRole(secondAdminId, Role.STUDENT),
                () -> adminUserService.changeRole(firstAdminId, Role.STUDENT));

        assertOneWonAndOneGotLastAdminRequired(outcome);
        assertThat(countAmongOurTwo("role = 'ADMIN' AND enabled = true"))
                .as("exactly one admin left; 0 means both requests counted 2 admins at the same time: "
                        + "see lockEnabledByRole in AdminUserService.changeRole")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Admin lock: the only two admins disable each other at once, exactly one admin is left")
    void twoAdminsDisablingEachOther_leaveExactlyOneEnabledAdmin() throws Exception {
        Outcome outcome = runAtTheSameMoment(
                () -> adminUserService.disable(secondAdminId), () -> adminUserService.disable(firstAdminId));

        assertOneWonAndOneGotLastAdminRequired(outcome);
        assertThat(countAmongOurTwo("role = 'ADMIN' AND enabled = true"))
                .as("exactly one enabled admin left; 0 means both requests counted 2 admins at the same time: "
                        + "see lockEnabledByRole in AdminUserService.disable")
                .isEqualTo(1);
    }

    /**
     * The first action runs as the first admin, the second as the second admin, each on its own thread and
     * in its own transaction, released together by a start gate and held by the commit gate.
     */
    private Outcome runAtTheSameMoment(Runnable asFirstAdmin, Runnable asSecondAdmin) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch readyToCommit = new CountDownLatch(2);
        Outcome outcome = new Outcome();

        try {
            Future<?> first = executor.submit(() -> act(firstAdminId, asFirstAdmin, ready, go, readyToCommit, outcome));
            Future<?> second =
                    executor.submit(() -> act(secondAdminId, asSecondAdmin, ready, go, readyToCommit, outcome));
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("both threads reached the start gate")
                    .isTrue();
            go.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            // shutdownNow() so a timeout does not leave the threads running, and awaitTermination so an
            // interrupted thread has finished before tearDown deletes its rows.
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        return outcome;
    }

    private void act(
            Long actorId,
            Runnable action,
            CountDownLatch ready,
            CountDownLatch go,
            CountDownLatch readyToCommit,
            Outcome outcome) {
        // AdminUserService reads the acting admin from the token (CurrentUser), one per thread.
        loginAs(actorId);
        ready.countDown();
        try {
            go.await();
            transactionTemplate.executeWithoutResult(status -> {
                action.run();
                holdCommitUntilTheOtherIsReady(readyToCommit);
            });
            outcome.succeeded.incrementAndGet();
        } catch (BusinessRuleException ex) {
            if ("LAST_ADMIN_REQUIRED".equals(ex.getCode())) {
                outcome.lastAdminRequired.incrementAndGet();
            } else {
                outcome.unexpected.add(ex);
            }
        } catch (Throwable t) {
            outcome.unexpected.add(t);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void assertOneWonAndOneGotLastAdminRequired(Outcome outcome) {
        assertThat(outcome.unexpected)
                .as("no other error than the last-admin rule")
                .isEmpty();
        assertThat(outcome.succeeded.get())
                .as("only the first request goes through; 2 means both counted 2 enabled admins at the same "
                        + "time: see lockEnabledByRole in AdminUserService.changeRole and disable")
                .isEqualTo(1);
        assertThat(outcome.lastAdminRequired.get())
                .as("the second one waits at the lock, then finds one admin left: 409 LAST_ADMIN_REQUIRED")
                .isEqualTo(1);
    }

    /**
     * Keeps this thread's transaction open, with every lock it holds, until the other thread is also ready to
     * commit, or until {@link #COMMIT_GATE_SECONDS} have passed. Running out of time is the expected case when
     * the lock works: the other thread is waiting at the lock and cannot get here before this one commits.
     */
    private static void holdCommitUntilTheOtherIsReady(CountDownLatch readyToCommit) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                readyToCommit.countDown();
                try {
                    readyToCommit.await(COMMIT_GATE_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    // The user id in "sub" and ROLE_ADMIN as an authority, so CurrentUser.id() and CurrentUser.isAdmin() both
    // answer as for a real admin request (the service only reads id(), but a copy of this should be complete).
    private static void loginAs(Long userId) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
                .header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("roles", List.of("ROLE_ADMIN"))
                .build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(context);
    }

    private Long insertAdmin(String email) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, '{bcrypt}hash', 'Race admin', 'ADMIN', true, NOW())
                RETURNING id
                """, Long.class, email);
    }

    private int countAmongOurTwo(String condition) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users WHERE id IN (?, ?) AND " + condition,
                Integer.class,
                firstAdminId,
                secondAdminId);
    }

    private static final class Outcome {
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger lastAdminRequired = new AtomicInteger();
        final List<Throwable> unexpected = new CopyOnWriteArrayList<>();
    }
}

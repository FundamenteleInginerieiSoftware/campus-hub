package com.campus.incident.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #269 point 1 (Matei's read-through): two admins resolve the last two incidents of one asset at the same
 * moment. Each resolve asks "is any other incident still keeping the asset out of use?". Without the asset
 * lock in {@code IncidentService.releaseAssetIfNoOtherIncident}, both ask before either commits, both still
 * see the other incident as active, and the asset stays UNDER_MAINTENANCE with no active incident. With the
 * lock, the second resolve waits for the first to commit, then sees no active incident and frees the asset.
 *
 * <p>Built like {@link IncidentQuotaConcurrencyIT}: not @Transactional, because each thread must really
 * commit; the data is inserted with plain SQL (generated ids, #346) and deleted in tearDown.
 *
 * <p>Why the commit gate: a plain start gate leaves it to luck whether the two transactions overlap. Here
 * each thread, once its resolve has run, holds its transaction open until the other one is also ready to
 * commit (at most {@link #COMMIT_GATE_SECONDS}). Without the lock both get there, so the race always
 * happens and the test always fails. With the lock the second thread is stuck at the lock, the first one
 * gives up waiting and commits, and the second one continues. So the test passes in about 3 seconds.
 */
@SpringBootTest
class IncidentResolveConcurrencyIT {

    private static final long COMMIT_GATE_SECONDS = 3;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long adminId;
    private Long studentId;
    private Long categoryId;
    private Long labRoomId;
    private Long assetId;
    private Long firstIncidentId;
    private Long secondIncidentId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        adminId = insertUser("admin-" + tag + "@incrace.test", "ADMIN");
        studentId = insertUser("student-" + tag + "@incrace.test", "STUDENT");
        categoryId = jdbcTemplate.queryForObject(
                "INSERT INTO categories (name) VALUES (?) RETURNING id", Long.class, "Incident race " + tag);
        labRoomId = jdbcTemplate.queryForObject(
                "INSERT INTO lab_rooms (code, name) VALUES (?, ?) RETURNING id",
                Long.class,
                "LAB-RACE-" + tag,
                "Incident race lab " + tag);
        // Out of use because of the two HIGH reports below, which are the only active incidents on it.
        assetId = jdbcTemplate.queryForObject("""
                INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, created_at, updated_at)
                VALUES ('Race scope', 'RACE-MODEL', ?, 'UNDER_MAINTENANCE', ?, ?, NOW(), NOW())
                RETURNING id
                """, Long.class, "SN-RACE-" + tag, categoryId, labRoomId);
        firstIncidentId = insertHighIncident("Screen is black");
        secondIncidentId = insertHighIncident("Probe connector is loose");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM audit_logs WHERE actor_id IN (?, ?)", adminId, studentId);
        jdbcTemplate.update("DELETE FROM incident_reports WHERE asset_id = ?", assetId);
        jdbcTemplate.update("DELETE FROM assets WHERE id = ?", assetId);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", adminId, studentId);
    }

    @Test
    @DisplayName("Asset lock: two admins resolve the last two incidents of one asset at once, the asset is freed")
    void twoParallelResolvesOfTheLastTwoIncidents_makeTheAssetAvailable() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch readyToCommit = new CountDownLatch(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        // Each thread resolves a different incident of the same asset, in its own transaction.
        LongConsumer resolve = incidentId -> {
            ready.countDown();
            try {
                go.await();
                transactionTemplate.executeWithoutResult(status -> {
                    incidentService.resolveIncident(incidentId, "Fixed in the lab", adminId);
                    holdCommitUntilTheOtherIsReady(readyToCommit);
                });
            } catch (Throwable t) {
                errors.add(t);
            }
        };

        // shutdownNow() in finally so a timeout does not leave the threads running, and awaitTermination so an
        // interrupted thread has finished before tearDown deletes its rows.
        try {
            Future<?> first = executor.submit(() -> resolve.accept(firstIncidentId));
            Future<?> second = executor.submit(() -> resolve.accept(secondIncidentId));
            assertThat(ready.await(5, TimeUnit.SECONDS))
                    .as("both threads reached the start gate")
                    .isTrue();
            go.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(errors).as("both resolves succeed").isEmpty();
        assertThat(jdbcTemplate.queryForList(
                        "SELECT status FROM incident_reports WHERE asset_id = ?", String.class, assetId))
                .as("both incidents are closed")
                .containsExactly("RESOLVED", "RESOLVED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM assets WHERE id = ?", String.class, assetId))
                .as("no incident keeps the asset out of use any more, so it must be bookable again. "
                        + "UNDER_MAINTENANCE here means both resolves checked before either committed: "
                        + "see the lock in IncidentService.releaseAssetIfNoOtherIncident")
                .isEqualTo("AVAILABLE");
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

    private Long insertUser(String email, String role) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, '{bcrypt}hash', 'Incident race user', ?, true, NOW())
                RETURNING id
                """, Long.class, email, role);
    }

    // HIGH and OPEN: keeps the asset out of use from the moment it is reported (#229).
    private Long insertHighIncident(String description) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO incident_reports (asset_id, reported_by_id, description, severity, status, created_at)
                VALUES (?, ?, ?, 'HIGH', 'OPEN', NOW())
                RETURNING id
                """, Long.class, assetId, studentId, description);
    }
}

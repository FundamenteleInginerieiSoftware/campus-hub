package com.campus.reservation.service.retry;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test proving that TransientFaultRetryExecutor intercepts a genuine PostgreSQL
 * deadlock (SQLSTATE 40P01) caused by inverted row locks across concurrent transactions,
 * retrying the victim transaction until both threads successfully complete.
 */
@SpringBootTest
class PostgresDeadlockIT {

    private Long assetA;
    private Long assetB;
    private Long categoryId;
    private Long labRoomId;

    @Autowired
    private TransientFaultRetryExecutor retryExecutor;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        // No fixed ids: let PostgreSQL generate them so the test never collides with seeded rows.
        labRoomId = jdbcTemplate.queryForObject("""
            INSERT INTO lab_rooms (code, name, building)
            VALUES ('LR-DEADLOCK-IT', 'Deadlock Lab', 'Building D')
            RETURNING id
            """, Long.class);

        categoryId = jdbcTemplate.queryForObject("""
            INSERT INTO categories (name, description)
            VALUES ('Deadlock Category', 'Used for deadlock integration testing')
            RETURNING id
            """, Long.class);

        assetA = insertAsset("Deadlock Asset A", "SN-DL-A");
        assetB = insertAsset("Deadlock Asset B", "SN-DL-B");
    }

    private Long insertAsset(String name, String serial) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO assets (name, model, serial_number, status, category_id, lab_room_id, version, created_at, updated_at)
            VALUES (?, 'MODEL_DL', ?, 'AVAILABLE', ?, ?, 0, NOW(), NOW())
            RETURNING id
            """, Long.class, name, serial, categoryId, labRoomId);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM assets WHERE id IN (?, ?)", assetA, assetB);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = ?", labRoomId);
    }

    @Test
    @DisplayName(
            "Genuine PostgreSQL deadlock (40P01) is intercepted by TransientFaultRetryExecutor and recovered successfully")
    void postgresDeadlock_isInterceptedAndRetriedSuccessfully() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch locksAcquiredLatch = new CountDownLatch(2);

        AtomicInteger t1Attempts = new AtomicInteger(0);
        AtomicInteger t2Attempts = new AtomicInteger(0);

        // Thread 1: Locks A first, then tries to lock B
        Future<String> f1 = executor.submit(() -> {
            bothStarted.countDown();
            bothStarted.await(5, TimeUnit.SECONDS);

            return retryExecutor.executeWithRetry(
                    () -> {
                        int attempt = t1Attempts.incrementAndGet();
                        TransactionTemplate tt = new TransactionTemplate(transactionManager);
                        tt.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

                        return tt.execute(status -> {
                            // Step 1: Lock Asset A
                            jdbcTemplate.queryForObject(
                                    "SELECT id FROM assets WHERE id = ? FOR UPDATE", Long.class, assetA);

                            if (attempt == 1) {
                                locksAcquiredLatch.countDown();
                                try {
                                    locksAcquiredLatch.await(5, TimeUnit.SECONDS);
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }

                            // Step 2: Try to lock Asset B (conflicts with Thread 2)
                            jdbcTemplate.queryForObject(
                                    "SELECT id FROM assets WHERE id = ? FOR UPDATE", Long.class, assetB);
                            return "T1_SUCCESS";
                        });
                    },
                    "Deadlock-Thread-1");
        });

        // Thread 2: Locks B first, then tries to lock A
        Future<String> f2 = executor.submit(() -> {
            bothStarted.countDown();
            bothStarted.await(5, TimeUnit.SECONDS);

            return retryExecutor.executeWithRetry(
                    () -> {
                        int attempt = t2Attempts.incrementAndGet();
                        TransactionTemplate tt = new TransactionTemplate(transactionManager);
                        tt.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

                        return tt.execute(status -> {
                            // Step 1: Lock Asset B
                            jdbcTemplate.queryForObject(
                                    "SELECT id FROM assets WHERE id = ? FOR UPDATE", Long.class, assetB);

                            if (attempt == 1) {
                                locksAcquiredLatch.countDown();
                                try {
                                    locksAcquiredLatch.await(5, TimeUnit.SECONDS);
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }

                            // Step 2: Try to lock Asset A (conflicts with Thread 1)
                            jdbcTemplate.queryForObject(
                                    "SELECT id FROM assets WHERE id = ? FOR UPDATE", Long.class, assetA);
                            return "T2_SUCCESS";
                        });
                    },
                    "Deadlock-Thread-2");
        });

        String r1 = f1.get(10, TimeUnit.SECONDS);
        String r2 = f2.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(r1)
                .as("thread 1 must finish after the retry; see TransientFaultRetryExecutor.executeWithRetry")
                .isEqualTo("T1_SUCCESS");
        assertThat(r2)
                .as("thread 2 must finish after the retry; see TransientFaultRetryExecutor.executeWithRetry")
                .isEqualTo("T2_SUCCESS");

        int totalAttempts = t1Attempts.get() + t2Attempts.get();
        assertThat(totalAttempts)
                .as(
                        "Total execution attempts must be >= 3 (at least one PostgreSQL deadlock occurred and was recovered)")
                .isGreaterThanOrEqualTo(3);
    }
}

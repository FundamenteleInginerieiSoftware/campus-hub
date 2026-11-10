package com.campus.reservation.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.campus.common.error.ConflictException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real PostgreSQL integration test demonstrating that concurrent requests using the exact same
 * idempotency key never result in duplicate executions.
 */
@SpringBootTest
class IdempotencyConcurrencyIT {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyRecordRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Long TEST_USER_ID = 888L;

    public record DummyPayload(String action, Long amount) {}

    public record DummyResponse(String confirmationId, String status) {}

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
            INSERT INTO users (id, email, password_hash, full_name, role, enabled, created_at)
            VALUES (888, 'idempotent.student@campus.test', '{bcrypt}hash', 'Idempotent Student', 'STUDENT', true, NOW())
            ON CONFLICT (id) DO NOTHING
            """);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE user_id = ?", TEST_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", TEST_USER_ID);
    }

    @Test
    @DisplayName("10 simultaneous threads with the same Idempotency-Key execute business action exactly once")
    void concurrentRequestsWithSameKeyExecuteActionExactlyOnce() throws InterruptedException {
        String key = "concurrency-key-" + UUID.randomUUID();
        DummyPayload payload = new DummyPayload("BOOK", 100L);
        int threadCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger businessExecutionCount = new AtomicInteger(0);
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ResponseEntity<DummyResponse> response = idempotencyService.execute(
                            key, TEST_USER_ID, "POST", "/api/v1/test/action", payload, DummyResponse.class, () -> {
                                businessExecutionCount.incrementAndGet();
                                // Simulate 30ms business logic processing
                                try {
                                    Thread.sleep(30);
                                } catch (InterruptedException ignored) {
                                }
                                return ResponseEntity.status(HttpStatus.CREATED)
                                        .body(new DummyResponse("CONF-999", "SUCCESS"));
                            });
                    if ("true".equals(response.getHeaders().getFirst("Idempotency-Replayed"))) {
                        outcomes.add("REPLAYED");
                    } else {
                        outcomes.add("ORIGINAL");
                    }
                } catch (ConflictException ce) {
                    outcomes.add("CONFLICT_409");
                } catch (Exception e) {
                    outcomes.add("UNEXPECTED_" + e.getClass().getSimpleName());
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Fire all threads simultaneously
        boolean finished = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(finished)
                .as("All concurrent tasks must finish within timeout")
                .isTrue();
        assertThat(businessExecutionCount.get())
                .as("Business action must be executed exactly ONCE despite 10 simultaneous threads")
                .isEqualTo(1);
        assertThat(outcomes).hasSize(threadCount);
        assertThat(outcomes).contains("ORIGINAL");
        // All threads must strictly resolve to ORIGINAL (1), REPLAYED, or CONFLICT_409 without leaking unexpected 500s
        assertThat(outcomes.stream().filter(o -> o.equals("ORIGINAL")).count()).isEqualTo(1);
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isIn("ORIGINAL", "REPLAYED", "CONFLICT_409"));
    }
}

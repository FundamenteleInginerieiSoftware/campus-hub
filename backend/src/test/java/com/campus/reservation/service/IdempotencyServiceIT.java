package com.campus.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campus.common.error.ConflictException;
import com.campus.reservation.domain.IdempotencyStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

// Real PostgreSQL: the bugs found in review (#262) came from flush order and the unique index,
// which Mockito can't show.
@SpringBootTest
class IdempotencyServiceIT {

    private static final String PATH = "/api/v1/test/action";

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private Long userId;

    public record DummyPayload(String action, Long amount) {}

    public record DummyResponse(String confirmationId, String status) {}

    @BeforeEach
    void setUp() {
        userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, full_name, role, enabled, created_at)
                VALUES (?, '{bcrypt}hash', 'Idempotent Student', 'STUDENT', true, NOW()) RETURNING id
                """,
                Long.class,
                "idem-" + UUID.randomUUID() + "@campus.test");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM idempotency_records WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    @DisplayName("10 simultaneous requests with one key run the action exactly once")
    void concurrentRequestsWithSameKeyExecuteActionExactlyOnce() throws InterruptedException {
        String key = "concurrency-" + UUID.randomUUID();
        DummyPayload payload = new DummyPayload("BOOK", 100L);
        int threadCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger runs = new AtomicInteger();
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    ResponseEntity<DummyResponse> response = run(key, payload, () -> {
                        runs.incrementAndGet();
                        sleep(30);
                        return created("CONF-999");
                    });
                    outcomes.add(isReplay(response) ? "REPLAYED" : "ORIGINAL");
                } catch (ConflictException e) {
                    outcomes.add("CONFLICT_409");
                } catch (Exception e) {
                    outcomes.add("UNEXPECTED_" + e.getClass().getSimpleName());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        boolean finished = done.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(finished).as("all threads must finish within 15 s").isTrue();
        assertThat(runs.get())
                .as("one key = one execution; see IdempotencyService.claimKey")
                .isEqualTo(1);
        assertThat(outcomes.stream().filter("ORIGINAL"::equals).count())
                .as("exactly one thread gets the original response, outcomes: %s", outcomes)
                .isEqualTo(1);
        assertThat(outcomes)
                .as("the others get a replay or 409, never a 500")
                .allSatisfy(o -> assertThat(o).isIn("ORIGINAL", "REPLAYED", "CONFLICT_409"));
    }

    @Test
    @DisplayName("After a FAILED attempt the same key can be retried and runs again")
    void failedKeyCanBeRetried() {
        String key = "failed-" + UUID.randomUUID();
        DummyPayload payload = new DummyPayload("BOOK", 1L);

        assertThatThrownBy(() -> run(key, payload, () -> {
                    throw new ConflictException("RESERVATION_LIMIT_REACHED", "limit");
                }))
                .as("the business error must reach the client unchanged")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("limit");
        assertThat(statusOf(key))
                .as("a failed attempt marks the key FAILED; see IdempotencyService.execute")
                .isEqualTo(IdempotencyStatus.FAILED.name());

        ResponseEntity<DummyResponse> retry = run(key, payload, () -> created("CONF-2"));

        assertThat(retry.getStatusCode())
                .as("retry after FAILED must run (was 409 for 24 h: delete + insert hit the unique index)")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(isReplay(retry)).as("a real run, not a replay").isFalse();
        assertThat(statusOf(key)).as("key COMPLETED after the retry").isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("A COMPLETED key with an unreadable body replays the status and never runs again")
    void completedWithUnreadableBodyIsNotRunAgain() {
        String key = "broken-" + UUID.randomUUID();
        DummyPayload payload = new DummyPayload("BOOK", 1L);
        insertRecord(key, payload, "COMPLETED", 201, "{not json", now());
        AtomicInteger runs = new AtomicInteger();

        ResponseEntity<DummyResponse> res = run(key, payload, () -> {
            runs.incrementAndGet();
            return created("SECOND");
        });

        assertThat(runs.get())
                .as("COMPLETED must never run again, even if the stored body can't be read; see replay()")
                .isZero();
        assertThat(res.getStatusCode()).as("stored status is replayed").isEqualTo(HttpStatus.CREATED);
        assertThat(isReplay(res)).as("marked as a replay").isTrue();
        assertThat(res.getBody()).as("unreadable body: status only").isNull();
    }

    @Test
    @DisplayName("A fresh IN_PROGRESS key answers 409")
    void freshInProgressIsConflict() {
        String key = "busy-" + UUID.randomUUID();
        DummyPayload payload = new DummyPayload("BOOK", 1L);
        insertRecord(key, payload, "IN_PROGRESS", null, null, now().minusSeconds(5));

        assertThatThrownBy(() -> run(key, payload, () -> created("X")))
                .as("a request started 5 s ago is still running; see ABANDONED_AFTER")
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("currently being processed");
    }

    @Test
    @DisplayName("An IN_PROGRESS key older than 60 s is abandoned and the retry runs")
    void staleInProgressIsTakenOver() {
        String key = "stale-" + UUID.randomUUID();
        DummyPayload payload = new DummyPayload("BOOK", 1L);
        insertRecord(key, payload, "IN_PROGRESS", null, null, now().minusHours(3));

        ResponseEntity<DummyResponse> res = run(key, payload, () -> created("AFTER-CRASH"));

        assertThat(res.getStatusCode())
                .as("an IN_PROGRESS row 3 h old is abandoned (crash); the retry must run, not 409 for 24 h")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(statusOf(key)).as("key COMPLETED after the retry").isEqualTo("COMPLETED");
    }

    private ResponseEntity<DummyResponse> run(
            String key, DummyPayload payload, Supplier<ResponseEntity<DummyResponse>> action) {
        return idempotencyService.execute(key, userId, "POST", PATH, payload, DummyResponse.class, action);
    }

    private void insertRecord(
            String key, DummyPayload payload, String status, Integer code, String body, LocalDateTime startedAt) {
        String fingerprint = idempotencyService.computeFingerprint(userId, "POST", PATH, payload);
        jdbcTemplate.update(
                """
                INSERT INTO idempotency_records
                    (idempotency_key, user_id, request_fingerprint, status, response_status, response_body,
                     created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                key,
                userId,
                fingerprint,
                status,
                code,
                body,
                Timestamp.valueOf(startedAt),
                Timestamp.valueOf(startedAt.plusHours(24)));
    }

    private String statusOf(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_records WHERE idempotency_key = ? AND user_id = ?",
                String.class,
                key,
                userId);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private static ResponseEntity<DummyResponse> created(String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(new DummyResponse(id, "SUCCESS"));
    }

    private static boolean isReplay(ResponseEntity<?> response) {
        return "true".equals(response.getHeaders().getFirst("Idempotency-Replayed"));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

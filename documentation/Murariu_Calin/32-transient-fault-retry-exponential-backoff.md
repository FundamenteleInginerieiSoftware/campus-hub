# 32. Transient Fault Retry Engine with Exponential Backoff and Bounded Jitter

**Author:** Calin Murariu (@Swiorx)  
**Date:** 2026-11-10  
**Branch:** `feat/reservation-deadlock-retry-backend`  

---

## Part 1: In simple terms

Under high concurrent booking traffic (e.g., student rush hours, multi-asset project kit reservations, and simultaneous slot hold confirmations), multiple database transactions concurrently acquire pessimistic row locks (`SELECT FOR UPDATE`) on lab equipment. 

In real PostgreSQL 18 environments, when two transactions attempt to lock the same resources in opposing or overlapping sequences, PostgreSQL's deadlock detector identifies a dependency cycle and aborts one transaction with SQLSTATE `40P01` (`deadlock detected`), or aborts due to lock acquisition wait timeouts (`55P03`).

Without intelligent fault tolerance:
1. The student whose transaction was aborted receives an unexpected HTTP 500 or generic failure error.
2. The user is forced to manually refresh and retry, causing frustration and confusing errors.

To solve this, we implemented a **Transient Fault Retry Engine**:
- **Automatic Concurrency Recovery**: Catches transient PostgreSQL lock contention and deadlocks and transparently re-executes the booking operation in a brand new database transaction.
- **Exponential Backoff**: Successive retry attempts back off exponentially (50ms $\rightarrow$ 100ms $\rightarrow$ 200ms) to allow the competing transaction to commit and release its locks.
- **Randomized Full Jitter**: If two colliding transactions retry at fixed intervals, they wake up at the exact same millisecond and re-deadlock each other (the classic "thundering herd" lock stampede). Full randomized jitter introduces unpredictable timing variance, ensuring one transaction acquires locks while the other is still waiting.
- **Immediate Fail-Fast on Business Rules**: Non-transient business rejections (such as overlapping bookings, booking in the past, or invalid durations) are never retried and return their standard HTTP 400/409 responses immediately.
- **Admin Telemetry Dashboard**: Lab administrators can inspect real-time recovery metrics via `GET /api/v1/admin/reservations/retry-metrics`.

**Honest note: is there a deadlock to retry today?** The two booking paths are deadlock-free by design: `createReservation` locks the asset row and then the user advisory lock, and `createMultiAssetReservation` sorts the asset ids before locking (see the comment in `ReservationService`). So the retry is a safety net with no known trigger in them yet. It covers future lock orders, lock timeouts, serialization failures and optimistic-lock conflicts. `PostgresDeadlockIT` triggers a real deadlock with raw `JdbcTemplate` row locks, not through the booking flow.

---

## Part 2: Technical details

### 1. Architecture & Transaction Boundaries
When PostgreSQL aborts a transaction due to a deadlock, the entire transaction block is irreversibly terminated (`current transaction is aborted, commands ignored until end of transaction block`). Retrying *within* the same active transaction or Hibernate session is impossible.

Therefore, our `TransientFaultRetryExecutor` intercepts requests at the controller boundary:
- Each retry attempt invokes the target method through Spring's proxy (`ReservationService`), triggering Spring's transaction interceptor to provision a brand new, clean `@Transactional` context and JDBC connection.
- If attempt 1 deadlocks, Spring cleanly rolls back the aborted transaction.
- The executor catches the concurrency failure, records the attempt in `RetryMetrics`, sleeps with jittered backoff, and re-invokes the service method.
- On success, `RetryMetrics` increments `successfulRecoveries`.

### 2. Component Design (`com.campus.reservation.service.retry`)
- **`RetryPolicy`**: Immutable record defining `maxAttempts` (default 3), `initialBackoff` (50ms), `maxBackoff` (1000ms), `backoffMultiplier` (2.0), and `jitterEnabled` (true).
- **`Sleeper`**: Functional interface `void sleep(long millis) throws InterruptedException` allowing instantaneous unit testing with zero artificial test delays.
- **`TransientFaultClassifier`**: Deterministic classifier. It retries only:
  - Spring's `ConcurrencyFailureException` family (`CannotAcquireLockException`, `DeadlockLoserDataAccessException`, `PessimisticLockingFailureException`, and also `ObjectOptimisticLockingFailureException`), on the exception itself or anywhere in its cause chain;
  - an `SQLException` in the cause chain whose SQLSTATE is one of:
    - `40P01`: `deadlock_detected`
    - `55P03`: `lock_not_available`
    - `40001`: `serialization_failure`

  Nothing else is retried: no `QueryTimeoutException`, no generic `TransientDataAccessException`, no `57014` (statement timeout). There is no fallback on the error message text; only the SQLSTATE counts.
- **Optimistic-lock conflicts are retried too.** `ObjectOptimisticLockingFailureException` is a `ConcurrencyFailureException`, so on the wrapped endpoints a `@Version` conflict (`Reservation`, `ReservationTransfer`) is retried and becomes `409 STALE_VERSION` only after 3 attempts. This is safe: each attempt runs in a fresh transaction and the service re-checks the reservation state, so a retry never applies a stale change.
- **`RetryMetrics`**: Thread-safe in-memory counters (`AtomicLong`) tracking `totalRetries`, `successfulRecoveries`, and `exhaustedRetries`. They are visible only through the admin endpoint below (Actuator exposes only `health,info`, so there are no Micrometer counters) and reset on restart.
- **`TransientFaultRetryExecutor`**: Coordinates loop execution, backoff calculation, jitter randomization, and SLF4J audit logging (`WARN` on retry, `INFO` on recovery, `ERROR` on exhaustion).

### 3. Jitter Calculation Formula
```java
long exponential = (long) (retryPolicy.initialBackoff().toMillis() * Math.pow(retryPolicy.backoffMultiplier(), attempt - 1));
long capped = Math.min(retryPolicy.maxBackoff().toMillis(), exponential);
return ThreadLocalRandom.current().nextLong(10, capped + 1);
```

### 4. Admin Telemetry Endpoint
- Path: `GET /api/v1/admin/reservations/retry-metrics`
- Security: Protected under `/api/v1/admin/**` requiring `ADMIN` role.
- Response DTO: `RetryMetricsResponse(long totalRetries, long successfulRecoveries, long exhaustedRetries)`.

### 5. Verification & Testing
- Unit suite in `TransientFaultRetryExecutorTest.java`:
  - Instant success on first attempt with 0 retries.
  - Recovery after simulated PostgreSQL deadlock (`SQLSTATE 40P01`).
  - Multi-step recovery across successive lock timeouts.
  - Exhaustion handling and rethrow of original deadlock exception after max attempts.
  - Fail-fast assertions on non-transient exceptions (`ConflictException`).
  - Bounded jitter distribution assertion.
  - Thread interruption handling.
  - SQLSTATE error classifier checks.
- Controller integration tests:
  - `AdminReservationControllerTest.java` verifying 200 response and JSON telemetry fields.
  - `ReservationControllerTest.java` verifying transparent execution through mock/real executor.
- `PostgresDeadlockIT.java` (`@SpringBootTest`, real PostgreSQL): two threads lock two asset rows in opposite order, PostgreSQL kills one with `40P01`, and the executor retries it until both threads finish. The `>= 3 attempts` assertion can only pass if a real deadlock happened. (`ReservationConcurrencyIT` tests the booking row lock, not this executor.)
- ArchUnit compliance: 100% green on module boundaries and coding conventions.

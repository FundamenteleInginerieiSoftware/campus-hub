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
- **`TransientFaultClassifier`**: Fast deterministic classifier that checks for Spring DAO exceptions (`DeadlockLoserDataAccessException`, `CannotAcquireLockException`, `PessimisticLockingFailureException`, `QueryTimeoutException`, `TransientDataAccessException`) as well as underlying `SQLException` SQLSTATE codes:
  - `40P01`: `deadlock_detected`
  - `55P03`: `lock_not_available`
  - `40001`: `serialization_failure`
  - `57014`: `query_canceled` (statement timeout)
- **`RetryMetrics`**: Thread-safe in-memory counters (`AtomicLong`) tracking `totalRetries`, `successfulRecoveries`, and `exhaustedRetries`.
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
  - Integration concurrency tests (`ReservationConcurrencyIT`) running against PostgreSQL.
- ArchUnit compliance: 100% green on module boundaries and coding conventions.

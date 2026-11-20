# 50 — UserDisabledEvent Listener in Reservation Module (#229)

## Part 1: In simple terms

When an admin disables a student account (`POST /api/v1/admin/users/{id}/disable`), the user module publishes `UserDisabledEvent` (#302). This PR wires the reservation module to listen for that event and automatically cancel all of the disabled student's upcoming `CONFIRMED` bookings (while leaving already `CHECKED_OUT` equipment untouched so it must still be returned at the desk).

Because disabling an account is a security kill switch, the listener runs **after** the disable transaction commits, inside its own fresh transaction, and catches/logs any error. Even if cancelling reservations ever fails, the account stays disabled and the admin can retry with `POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming` (#266).

---

## Part 2: Technical details

| File | Change |
|---|---|
| `UserDisabledReservationListener.java` | `@Service` in `com.campus.reservation.service` listening to `UserDisabledEvent` with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`. Uses `TransactionTemplate` (`PROPAGATION_REQUIRES_NEW`) inside `try/catch(RuntimeException)` so both business errors and commit/flush failures land in the `catch` block, roll back the inner transaction cleanly, and emit the actionable `POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming` retry log line. Not `@Async`, so `CurrentUser` stays bound to the request thread and `audit_logs.actor_id` records the admin. |
| `UserDisabledReservationListenerTest.java` | Unit tests verifying the `AFTER_COMMIT` + `REQUIRES_NEW` + non-`@Async` contract, happy-path delegation to `ReservationApi.cancelUpcomingReservationsForUser`, and exception swallowing with inner `rollback(...)` (never `commit(...)`). |
| `UserDisabledReservationListenerIT.java` | Non-`@Transactional` PostgreSQL IT calling `POST /api/v1/admin/users/{id}/disable` via `MockMvc` (verifying `CONFIRMED` → `CANCELLED`, `CHECKED_OUT` untouched, `audit_logs.actor_id = adminId`), rolled-back transaction non-execution, and a PostgreSQL `BEFORE UPDATE` trigger failure case proving disable still returns 200 with `enabled = false` and manual `cancel-upcoming` recovers idempotently (1 then 0). |
| Docs | Updated doc `27-cancel-upcoming-reservations-for-user-facade.md` line 39 (from synchronous `@EventListener` to `AFTER_COMMIT` + `REQUIRES_NEW`, per Matei's review on #302), `docs/whats-left.md` (also removed the outdated "No new features" line and marked ArchUnit strict mode already active per #322 §4), and `AGENTS.md` §7. |

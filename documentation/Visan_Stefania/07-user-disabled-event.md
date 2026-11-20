# Disabling a User Frees Their Bookings (Vișan Ștefania)

**Role:** Member 4
**Date:** 2026-11-19
**Package:** `com.campus.user.admin` (+ `UserDisabledEvent` in `com.campus.user.api`, with Matei's OK)
**PR:** #302 (part 2 of #229, with Calin's #266 and his listener)

---

## Part 1: In simple terms

**The problem (found by Matei):** when an admin disabled an account, that student's future bookings stayed active. The equipment stayed blocked until the no-show job ran, and if it broke, the app still moved the booking and emailed the disabled student.

**The fix:** disabling an account now **announces** it ("user 5 was disabled by admin 1"). Right after the account is switched off, the reservations part of the app hears it and cancels that student's **upcoming** bookings. Equipment a student already took home stays as it is: it has to come back to the desk.

**The order matters:** first the account is disabled and saved, then the bookings are cancelled. Disabling an account is how an admin stops someone at once, so it must work even if cancelling the bookings fails. If that second step ever fails, the account is still disabled, the error is logged, and the admin can cancel the bookings again with one button (Calin's endpoint from #266).

---

## Part 2: Technical details

### Why an event and not a direct call

`reservation` already depends on `user` (`UserApi`, `Role`). If `AdminUserService` called `ReservationApi` directly, `user` would also depend on `reservation`: a cycle, which ArchUnit's `no_cycles_between_modules` refuses. With an event, the arrow stays `reservation → user` (the same Observer idea as `AssetDamagedEvent`, `docs/patterns/observer-pattern.md`).

| Piece | Owner | What it does |
|---|---|---|
| `user.api.UserDisabledEvent(userId, adminId)` | event in `user.api` (Matei's package, added with his OK in #302) | the message |
| `AdminUserService.disable` | Stefania | publishes it after `user.disable()` and the audit row, inside the `@Transactional` method |
| listener in `reservation` | Calin | `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` → `ReservationApi.cancelUpcomingReservationsForUser(userId, reason)` (#266) |

### After the commit, on purpose (Matei's review)

My first version used a plain `@EventListener`, so the cancel joined the disable transaction. Matei showed with a probe test that a throwing listener then rolls the disable back: the account stays enabled. For a security kill switch that is the wrong way round. So the contract in the `UserDisabledEvent` Javadoc is now:

- published **inside** the disable transaction;
- listeners run **after the commit** (`AFTER_COMMIT`), in **their own** transaction (`REQUIRES_NEW`, because the original one is already finished);
- listeners **catch and log** their errors; the admin can rerun `POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming`;
- listeners are **not `@Async`**: the audit actor comes from `CurrentUser`, which is tied to the request thread.

This is the same contract as `AssetDamagedEvent` and `AssetRetiredEvent`.

### When the event is NOT published

Refused requests (yourself → `CANNOT_MODIFY_SELF`, last admin → `LAST_ADMIN_REQUIRED`, unknown id → 404) and disabling an account that is already disabled. Nothing changed, so there is nothing to cancel.

### A race Calin closes on his side

A booking that passed the token check just before the disable can wait on the per-user lock (`lockUserQuota`) and book right after the cancel ran. Matei suggested re-checking `userApi.findActiveRole(userId)` right after `lockUserQuota` in the booking paths; that is in Calin's module and his listener PR.

### Tests

- `AdminUserServiceTest`: the event is published once, after the audit row, with the user and the admin; not published for yourself, the last admin, an already disabled user or an unknown id.
- `AdminUserFlowIT` (real app, `@RecordApplicationEvents`): disable twice plus a refused self-disable → exactly one `UserDisabledEvent(studentId, adminId)`.
- The cancelling itself, and that it runs after the commit, are tested in Calin's module (#266 and his listener PR).

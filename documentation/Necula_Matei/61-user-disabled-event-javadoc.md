# `UserDisabledEvent` Javadoc corrected (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-20
**Branch / PR:** `fix/user-disabled-event-javadoc` (#335)
**See also:** `UserDisabledReservationListener` (Calin, #330), `LocalAssetRetiredListener` (Calin, #311), issue #229

---

## Part 1: In simple terms

1. **What was wrong:** the code comment on `UserDisabledEvent` (the message sent when an admin disables an account) told teammates how to write a listener for it. Two things in it were not true.
2. **What we did:** rewrote the comment so it matches the real code and explains the reasons, so a teammate who copies the pattern does not copy a mistake.
3. **Result:** no behaviour changed, only the comment. Found while reviewing Calin's #330.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `backend/src/main/java/com/campus/user/api/UserDisabledEvent.java` | Javadoc only |

### The two wrong claims

| Old text | Reality | New text |
|---|---|---|
| "the same contract as `AssetDamagedEvent` and `AssetRetiredEvent`" | The reassignment listeners of those events are `@Async`. The disable listeners are not: they run on the admin's request thread, because the audit actor comes from `CurrentUser`, which is bound to that thread | Says "unlike" and gives the reason |
| listeners use `@Transactional(propagation = REQUIRES_NEW)` | Calin's `UserDisabledReservationListener` (#330) opens its transaction with a `TransactionTemplate` (REQUIRES_NEW), not the annotation | Names the listener and the `TransactionTemplate` |

Two reasons were also missing and are now written down:
- In `AFTER_COMMIT` the disable's transaction is already finished. A listener that joined it would never commit its writes, so it needs a fresh `REQUIRES_NEW`.
- Spring already logs and swallows an exception from an `AFTER_COMMIT` listener, so the disable cannot fail either way. The listener's own catch exists to log the admin retry hint (`POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming`).

### Connections

| Module | How |
|---|---|
| Calin, `reservation` | `UserDisabledReservationListener` (#330) is the first listener; `LocalAssetRetiredListener` (#311) is the `@Async` pattern the old text confused it with |
| Stefania, `user/admin` | `AdminUserService.disable` publishes the event (#302) |

### How it was checked

| Command | Result |
|---|---|
| `./gradlew compileJava` | passes |
| `./gradlew spotlessCheck` | passes |

No tests: Javadoc only.

### Problems and fixes

None. Found by reading #330 next to the Javadoc.

# `findActiveRole` reads the row fresh (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-19
**Branch / PR:** `fix/user-active-role-fresh-read` (#TBD)
**See also:** `22-token-account-check.md`, Calin's #310, #229, #302

---

## Part 1: In simple terms

1. **The rule:** when an admin disables a student, that student must lose access at once, also for a booking they were in the middle of making.
2. **What I found:** while reviewing Calin's #310, I ran a test with two threads: the admin disables a student while the student's booking is waiting. The booking still went through. This happened for a bundle booking (also Projects "Reserve all"), for confirming a hold and for accepting a transfer.
3. **Why:** the booking code looks the student up, waits for its turn, then asks me "is this account still active?". My answer came from the copy of the student it had already loaded, not from the database. The copy still said "enabled".
4. **The fix:** the check now asks the database directly, every time, and gets a fresh answer. It is still one small lookup per request.
5. **The other half:** Calin also changes the order in his own code (wait for the turn first, then check) in #310. Both fixes are needed together.

**Result:** a disabled account is seen by the re-check. A new test proves it; it fails with the old code.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/repository/UserRepository.java` | New `findActiveRoleById`: `select u.role from User u where u.id = :id and u.enabled = true` |
| `user/service/UserService.java` | `findActiveRole` returns that query (was `findById(...).filter(enabled).map(role)`), with a bug-fix comment |
| `user/service/UserServiceTest.java` | Stubs use `findActiveRoleById`; the unused `user(...)` helper is removed |
| `user/service/FindActiveRoleFreshReadIT.java` (new) | The regression test below |

### Why it failed and why the fix works

| | |
|---|---|
| Bug | Inside a transaction, `findById` returns the `User` already in the persistence context (the first-level cache) without asking the database. A disable committed by another transaction is not visible. |
| Who is hit | Calin's re-check after the per-user quota advisory lock (#310): `ReservationService` bundle path (incl. Projects "Reserve all"), `HoldService.confirmHold`, `TransferService.acceptTransfer`. Related: #229 (disabled users) and #302. |
| Fix | A scalar query (`select u.role`) is not served from the persistence context: it flushes, then reads the row. Disabled or missing gives an empty `Optional`, same contract as before (`UserApi.findActiveRole`). |
| Cost | Still one primary-key lookup per request for the security filter (#128). |

### Connection to teammates

| Who | What |
|---|---|
| Calin | #310 calls `UserApi.findActiveRole` after the advisory lock. He also moves the check after the lock in his own paths. No signature change on `UserApi`. |
| Stefania | The disable endpoint (`/api/v1/admin/users/{id}/disable`, #206) is what the probe used. No change there. |

### How it was tested

| Test | What it checks |
|---|---|
| `FindActiveRoleFreshReadIT` (`@SpringBootTest`, PostgreSQL) | Load the user in a transaction; a `REQUIRES_NEW` transaction disables the user and commits; `findActiveRole` in the first transaction must be empty. Fails with the old `findById` code, passes now. |
| `UserServiceTest` | Enabled gives the role; disabled or missing gives empty (repository mocked). |
| Full build | `cd backend && ./gradlew spotlessApply build` → BUILD SUCCESSFUL, 1182 tests. |

### Problem and fix

| Problem | Fix |
|---|---|
| The bug only shows with two threads or two transactions, so a normal single-request test passes. | The IT forces it with `REQUIRES_NEW`, no sleeping or timing. |

# Whole seconds for a user's creation time (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-04
**Branch / PR:** `fix/user-created-at-seconds` (closes #134)
**See also:** `03-user-entity.md` (the users table), `13-auth-register-login.md` (register), `14-user-seeder.md`, `18-prod-admin-bootstrap.md`

---

## Part 1: In simple terms

Every account remembers when it was created. Two parts of the system kept that time with different precision.

1. **The program's clock is very precise.** It gives the time down to a billionth of a second.
2. **The database keeps only a millionth of a second.** It quietly rounds the rest away when it saves the account.
3. **So the same account had two slightly different creation times:** the one in memory right after saving, and the one read back later. Nobody sees this time today, but it is the kind of small mismatch that breaks a test or a comparison later.
4. **The fix:** the account now keeps its creation time in whole seconds, the same way every time is written in our API (`2026-11-04T22:00:05`). Calin made the same fix for reservations in #130.
5. **One place fixes all three ways an account is made:** registering, the dev test accounts and the first admin on AWS.

**Result:** the creation time saved and the one read back are always the same.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/domain/User.java` | Constructor: `createdAt.truncatedTo(ChronoUnit.SECONDS)` + bug-fix comment |
| `test/.../user/repository/UserRepositoryTest.java` | New test `storesCreatedAtInWholeSecondsSoTheSavedAndReloadedUserAgree` |
| `02-files-explained.md` | `User` and `UserRepositoryTest` rows |

### Why in the entity, not in each caller

`AuthService.register`, `UserSeeder` and `AdminBootstrap` all create users with `LocalDateTime.now(clock)`. Putting the truncation in the one public `User` constructor covers all three and any future caller, so there is one rule to explain. (Liviu's `AssetService` and Stefania's `IncidentService` do it in a `now()` helper instead, because there only one service creates the rows.)

### Why the bug was invisible

`createdAt` is not in any response record (`UserResponse`, `UserSummary`), so no endpoint showed the difference. The effect was only inside the server: Java's `LocalDateTime` has nanosecond precision, PostgreSQL `TIMESTAMP` has microsecond precision, so `saved.getCreatedAt()` ≠ the value after a fresh `findById`.

### Test

The test saves a user with `22:00:05.123456789`, calls `entityManager.clear()` (so `findById` really runs a `SELECT` instead of returning the cached entity), and checks both values are `22:00:05`.

| Check | Result |
|---|---|
| New test without the fix | **fails** (in-memory value keeps the nanoseconds) |
| New test with the fix | passes |
| `./gradlew spotlessApply build --rerun` | BUILD SUCCESSFUL, 354 tests, 0 failures (local PostgreSQL 18) |

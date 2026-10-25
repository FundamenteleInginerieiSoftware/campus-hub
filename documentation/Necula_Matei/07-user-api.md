# UserApi: how other modules ask about users (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-21
**Branch / PR:** `feat/user-api`
**See also:** `03-user-entity.md` (the users table), AGENTS.md §9 (agreed contracts)

---

## Part 1: In simple terms

Other parts of the app need to ask "who is user 5?": bookings show who booked, damage reports show who reported, e-mails need an address. They are not allowed to open the users table themselves.

1. **Added the agreed "front desk" for users** (`UserApi`): "give me user 5" and "does user 5 exist?".
2. **Added the answer card** (`UserSummary`): id, e-mail, name, role. Never the password.
3. **Only the contract for now.** The part that really answers is my next step. It uses Liviu's shared "not found" error, which is now on `main`. Teammates can already build and test against the contract.

**Result:** Calin and Stefania can write code that looks up users today.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/api/UserApi.java` | New: facade interface `getById(id)`, `exists(id)` (contract only) |
| `user/api/UserSummary.java` | New: `record UserSummary(id, email, fullName, role)` |

### Key choices

| Choice | Why |
|---|---|
| Exactly the agreed signatures (handbook / AGENTS §9) | Teammates already code against them; never break them without telling consumers |
| In `user.api` | Other modules may import only `api` (ArchUnit will enforce it). `User` and `UserRepository` stay internal |
| `UserSummary` without password hash and `enabled` | Internals can't leak to other modules |
| Javadoc on each method | It is the contract teammates read |
| Interface now, implementation in the next PR | Teammates can code against `UserApi` today and mock it with Mockito in unit tests (AGENTS §9, "if blocked"). `getById` will throw Liviu's `NotFoundException("USER_NOT_FOUND", …)`, on `main` since #22 |

### How it connects to teammates

| Who | What they get |
|---|---|
| Calin | Reservation owner, reassignment notifications |
| Stefania | Incident reporter |
| Liviu / Matei | E-mail address for `NotificationService` |

### Testing

| Command | Result |
|---|---|
| `cd backend && ./gradlew spotlessApply build` | BUILD SUCCESSFUL, **24 tests** (no new tests: an interface and a record have no logic; `UserService` will get its tests) |

### Next

| Step | Waits for |
|---|---|
| `UserService implements UserApi` | Nothing: Liviu's `NotFoundException(code, msg)` is on `main` (#22) |

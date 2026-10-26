# Dev accounts on startup (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-26
**Branch / PR:** `feat/auth-user-seeder`
**See also:** `13-auth-register-login.md` (login), `docs/api-contract.md` §3 (dev seed accounts)

---

## Part 1: In simple terms

To try the app, everyone needs accounts to log in with. Creating them by hand after every database reset is slow, so the app now creates them itself.

1. **When the app starts on a laptop, it creates 3 test accounts:** two students and one admin, all with the password `password`.
2. **Only if there are no users yet,** so restarting the app doesn't create copies.
3. **Only on laptops (the dev setup),** never in the tests and never on AWS.
4. **The students come first,** so Calin's test bookings land on student accounts and not on the admin.

**Result:** after `bootRun`, anyone can log in from Swagger as `student1@campus.test` or `admin@campus.test` and try their endpoints with a real token.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/service/UserSeeder.java` | New: `@Profile("dev") @Order(1)` `CommandLineRunner` |
| `test/.../user/service/UserSeederTest.java` | New: 2 Mockito tests |
| `02-files-explained.md` | Rows for both files |

### Accounts

| id | Email | Role | Password |
|---|---|---|---|
| 1 | `student1@campus.test` | STUDENT | `password` |
| 2 | `student2@campus.test` | STUDENT | `password` |
| 3 | `admin@campus.test` | ADMIN | `password` |

The ids hold on a fresh database (identity starts at 1, `saveAll` inserts in list order).

### Key choices

| Choice | Why |
|---|---|
| `CommandLineRunner` | Spring Boot calls every runner once after startup. Calin's `ReservationSeeder` uses the same interface, so all seeders look alike |
| `@Order(1)` | Boot runs runners sorted by `@Order`: users (1), then Liviu's catalog (2), then Calin's reservations (3), which need users and assets to exist |
| `@Profile("dev")` | The default profile on laptops is `dev` (`application.yml`); tests run as `test`, AWS as `prod`, so neither gets accounts with a known password |
| `count() > 0` → skip | Runs on every start: only an empty table is filled |
| Students first | Calin's seeder books for `select id from users order by id limit 2`, so ids 1–2 must be students. It also matches the contract examples (`"id": 1` = student1) |
| One hash for all three | Same password, so one `encode` is enough. Each BCrypt hash has its own salt, so a real app would hash per user; for dev accounts it doesn't matter |
| `User` constructor + shared `Clock` | Same path as register: email lowercased, `created_at` from `LocalDateTime.now(clock)` |

### Tests

| Test | Checks |
|---|---|
| `seedsTwoStudentsAndOneAdminIntoAnEmptyTable` | empty table → `saveAll` with student1, student2, admin in that order, every hash matches `password` |
| `doesNothingWhenUsersExist` | `count() = 3` → `saveAll` never called |

`./gradlew spotlessApply build`: **BUILD SUCCESSFUL, 63 tests** (61 on `main` + 2 new).

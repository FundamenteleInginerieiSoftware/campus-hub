# The whole demo as one test (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-06
**Branch / PR:** `test/demo-flow`
**See also:** `25-security-pass-tests.md` (same style of test), AGENTS.md §7 "Prototype demo" (the flow this test follows)

---

## Part 1: In simple terms

1. **The demo we show at the defense** is a chain: a student signs up, the admin adds two identical microscopes, the student books one, tries to book the same time again (refused), reports it broken, and the booking moves by itself to the second microscope.
2. **The professor will try to break something.** If the chain stops working, we want to know at once which link broke and whose part it is.
3. **The test plays the whole chain** through the same web addresses the website uses, in 10 numbered steps.
4. **When a step fails, the message says** "Step 6 (incident, Stefania): ... see IncidentController/IncidentService" and shows the server's answer. The steps after it are marked "skipped", so only the real culprit is red.
5. **The booking date is always "next weekday"**, worked out from today, so the test never goes out of date.
6. **It cleans up after itself**, so it can run as often as we want.

**Result:** one command (`./gradlew test --tests DemoFlowIT`) checks the whole demo in about 3 seconds (plus app start-up) and points at the broken part.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/src/test/java/com/campus/DemoFlowIT.java` | New: 10 ordered steps, helpers, `@AfterAll` clean-up. Root test package (mine), no production code changed |
| `02-files-explained.md`, `README.md` (this folder) | New rows |

### Steps → endpoint → owner

| Step | Request | Expected | Owner, where to look |
|---|---|---|---|
| 1 | `POST /api/v1/auth/register`, `POST /auth/login` | 201, 200 + token | Matei, `AuthController` / `AuthService` |
| 2 | admin login, `POST /api/v1/admin/categories`, `/lab-rooms`, `/assets` ×2 (same model) | 201 each | Liviu, catalog controllers |
| 3 | `POST /api/v1/reservations` (asset A, 10:00-11:00) | 201, `CONFIRMED` | Calin, `ReservationService.createReservation` |
| 4 | same slot again | 409 `RESERVATION_CONFLICT` | Calin, `existsOverlap` |
| 5 | `GET /api/v1/reservations/me` | exactly that booking | Calin, `getMyReservations` |
| 6 | `POST /api/v1/incidents` (`severity: HIGH`) | 201 | Stefania, `IncidentService.reportIncident` |
| 7 | `GET /api/v1/assets/{A}` | `UNDER_MAINTENANCE` | Stefania + Liviu, `markUnderMaintenance` |
| 8 | book A at 14:00 (free slot) | 409 `ASSET_NOT_BOOKABLE` | Calin, `isBookable` check |
| 9 | `GET /api/v1/reservations/{id}` | `assetId` = B, `originalAssetId` = A, `CONFIRMED` | Calin, `LocalAssetDamagedListener` → `ReassignmentService` → `SameModelStrategy` |
| 10 | `GET /api/v1/admin/reassignments?size=100` | one entry: `REASSIGNED`, A → B, `SameModelStrategy` | Calin, `ReassignmentBookingProcessor` |

### Key choices

| Choice | Why |
|---|---|
| Not `@Transactional` (unlike `SecurityPassIT`) | Calin's listener is `@TransactionalEventListener(AFTER_COMMIT)`. In a test transaction that is rolled back, the incident never commits, so the reassignment would never run |
| `@AfterAll cleanUp()` with `JdbcTemplate` | Because nothing is rolled back. Deletes children before parents (log, incident, audit, reservations, assets, lab room, category, users). Lab room codes are stored in upper case by Liviu's `LabRoom`, so the delete upper-cases the code |
| Unique suffix per run (`RUN`) | Emails, names and the **model** never clash with old rows: the engine finds substitutes by model, so an old "DEMO" asset could be picked instead of B |
| Admin inserted with SQL, then real login | Register only makes students and the dev seeder doesn't run in tests (same as `SecurityPassIT`) |
| No waiting in step 9 | In the `test` profile `AsyncConfig` uses a `SyncTaskExecutor`, so the `@Async` listener runs on the request thread right after the commit: when `POST /incidents` returns, the move is done |
| `PER_CLASS` + `@Order` + `passedStep` + `assumeTrue` | Ids and tokens carry over between steps; a failed step makes the later ones *skipped*, not failed, so only the broken step is red |
| `call(step, status, request)` helper | One place that checks the status and puts the whole body (with the ProblemDetail `code`) into the message |

### Why the date is computed, not a fixed Clock

| Option | Problem |
|---|---|
| Hard-coded date (`2026-11-02T14:00`) | Breaks once the date passes (issue #94) |
| Fixed `Clock` bean in a `@TestConfiguration` | Login signs tokens with the app `Clock`, but the token check uses the real time, so tokens from a frozen date expire a few hours later; it would also start a second Spring context |
| **Chosen:** next weekday after `LocalDate.now(clock)`, 10:00 | Always in the future, Mon-Fri, inside 08:00-20:00 and the 14-day horizon (Calin's rules) |

### How it was tested

Scratch PostgreSQL 18 on port 5435, `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5435/campus_test`.

| Check | Result |
|---|---|
| `./gradlew spotlessApply build --rerun` before (main `99d0261`) | 402 tests |
| Same with `DemoFlowIT`, on a fresh `campus_test` | BUILD SUCCESSFUL, 412 tests |
| `DemoFlowIT` alone | 10 green, ≈ 17 s (most of it is Spring start-up); ≈ 3 s inside the full build, where the context is already started |
| Rows left in the database after the run | 0 users, assets, lab rooms, categories |

**Break experiments** (each break in production code was reverted right after; `git diff -- backend/src/main` is empty):

| Break | Result |
|---|---|
| Overlap check turned off (`false && existsOverlap(...)` in `ReservationService`) | Step 4 failed, 5-10 skipped: `Step 4 (overlap check, Calin): POST /api/v1/reservations for a taken slot should return 409; see ReservationService.createReservation (existsOverlap): expected HTTP 409 but got 201, body: {"id":67,...,"status":"CONFIRMED",...}` |
| `catalogApi.markUnderMaintenance(...)` commented out in `IncidentService` | Step 7 failed, 8-10 skipped: `Step 7 (incident -> catalog, Stefania + Liviu): GET /api/v1/assets/{id} should show UNDER_MAINTENANCE; see IncidentService.reportIncident (markUnderMaintenance) and CatalogService: wrong status, body: {...,"status":"AVAILABLE",...} expected: "UNDER_MAINTENANCE" but was: "AVAILABLE"` |
| `reassignmentService.handle(event)` commented out in `LocalAssetDamagedListener` | Step 9 failed, 10 skipped: `Step 9 (reassignment, Calin): ... expected assetId=50 (B), originalAssetId=49 (A), still CONFIRMED; body: {...,"assetId":49,"originalAssetId":null,...} ... ["assetId"=49 (expected: 50)]` |

### Problems hit

| Problem | Fix |
|---|---|
| The first clean-up left lab rooms behind: Liviu's `LabRoom` upper-cases the code (`D-mw457vv6` → `D-MW457VV6`) | Delete by `RUN.toUpperCase(Locale.ROOT)`; checked 0 rows left |
| Upper-casing the whole suffix instead would break login: `User` lower-cases emails, but the admin is inserted with SQL as typed (seen in the code, before running it) | Suffix stays lower case; only the lab room delete upper-cases it |
| A rerun on the same database once failed in `SecurityPassIT` (register 409). Cause: Calin's `ReassignmentRollbackIT` inserts a user with a fixed `id = 100` and never deletes it; when the id counter reaches 100, the next register hits `users_pkey` and `AuthService` answers 409 `EMAIL_TAKEN` | Not my code: to report to Calin (issue or PR comment). One build uses about 39 user ids, so CI (fresh database) is safe; on a laptop it hits once around the third build. Workaround: reset `campus_test` |

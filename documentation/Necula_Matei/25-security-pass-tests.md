# Security pass: IDOR, admin-only endpoints and error bodies, as tests (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-05
**Branch / PR:** `test/security-pass` (test-only, no production code changed)
**See also:** `04-password-jwt-roles.md` (security rules), `05-security-error-json.md` (401/403 JSON), `12-error-codes-fix.md`, `16-security-probe-paths.md`

---

## Part 1: In simple terms

The plan has a "security pass" in W10: check by hand that nobody can see or change what isn't theirs. I wrote it as tests instead, so it runs on every pull request and keeps checking after W10.

1. **The test starts the whole app** on the test database, creates an admin and two students (A and B), and logs them in for real, the way the website does.
2. **The admin adds one piece of equipment**, and student A books it for tomorrow.
3. **Student B tries to open and cancel A's booking.** The app answers "not found", exactly as for a booking that doesn't exist. So B can't even find out which bookings exist. A's booking stays as it was.
4. **B's "My reservations" list is empty**; A's list shows A's booking.
5. **B tries to cheat** by writing A's user number into a booking and an incident report. The app ignores it: both are saved under B's name, because the app only trusts the login token.
6. **The test asks Spring for the list of every admin address** (16 today) and calls each one as a student and without a login. Every one answers "not allowed" (403) or "log in first" (401). When a teammate adds a new admin address, the test checks it automatically.
7. **The test makes the app fail in many ways** (unknown page, broken data, missing field, double booking, wrong method, no login, a database error) and checks that every answer is a short, tidy error with a code, and never shows program internals (class names, stack traces, SQL).
8. **Before committing, I broke the app on purpose** three ways (removed the owner check, opened the admin addresses to students, let the error handler show the real message). The tests went red each time, so they really catch these mistakes.

**Result:** 7 new tests, all green; no security bug found in the code on `main`.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/src/test/java/com/campus/config/SecurityPassIT.java` | New: 7 tests + a test-only `FailingController` |
| `documentation/Necula_Matei/25-security-pass-tests.md` | This doc |
| `documentation/Necula_Matei/02-files-explained.md` | Rows for the new test |

### Setup (`@BeforeEach`)

| Step | How | Why |
|---|---|---|
| Admin user | `JdbcTemplate` INSERT with a BCrypt hash from our `PasswordEncoder`, then `POST /api/v1/auth/login` | Register only makes students. The login is real, so the token is exactly what the frontend gets |
| Students A and B | `POST /api/v1/auth/register` + login | Real tokens; their ids are real rows, so the FKs from reservations and incidents hold |
| Asset | Liviu's `POST /api/v1/admin/categories`, `/lab-rooms`, `/assets` | Through the public API, no repository from another module |
| A's booking | Calin's `POST /api/v1/reservations`, next weekday 10:00-11:00 | Fits his rules (Mon-Fri 08-20, max 14 days ahead, :00/:30) |
| Cleanup | `@Transactional` on the class | MockMvc runs on the test thread, so everything is rolled back after each test |

**No fixed `Clock`:** the slot is computed from Calin's shared `Clock` bean ("next weekday"). A fixed clock would also sign the login tokens at that fixed time, but the JWT decoder checks expiry against the real time, so the test would turn red 8 hours after the fixed date.

### What each test proves

| Test | Request | Expected | Why |
|---|---|---|---|
| `otherStudentCannotReadMyReservation` | B: `GET /api/v1/reservations/{A's id}` and `GET /api/v1/reservations/999999999` | Both 404 `RESERVATION_NOT_FOUND` (A gets 200) | AGENTS.md §5: others' resources are 404, not 403, so ids can't be enumerated |
| `otherStudentCannotCancelMyReservation` | B: `POST /api/v1/reservations/{A's id}/cancel` | 404, then A still sees `CONFIRMED` | A state change on someone else's resource is the worst IDOR |
| `myListsOnlyShowMyOwnReservations` | B: `GET /reservations/me`, `/me/summary`; A: `/me` | B: 0 / 0; A: 1, his id | The lists filter by the token's user |
| `userIdInTheBodyIsIgnored` | B: booking with `"userId": A`, incident with `"reportedById": A` | 201, owner = B in both | Mass assignment: the owner comes from `CurrentUser`, never from the body |
| `everyAdminEndpointIsClosedToStudentsAndAnonymousCallers` | Every `/api/v1/admin/**` mapping, path variables = 1 | Student: 403 `FORBIDDEN`; no token: 401 `UNAUTHORIZED` | Covers new admin endpoints without editing the test |
| `errorBodiesAreCleanProblemDetails` | 9 error cases (table below) | `application/problem+json`, the right `code`, no leak markers | The frontend relies on `code`; internals help attackers |
| `unexpectedErrorHidesTheDatabaseMessage` | Test-only endpoint throws `DataIntegrityViolationException` with SQL in its message | 500 `INTERNAL_ERROR`, no SQL, no constraint name | The usual real 500 is a DB error; Liviu's catch-all must hide it |

**Admin endpoints found on `main` (16):** `POST/PUT/DELETE` categories, `POST/PUT/DELETE` lab-rooms, `POST /admin/assets`, `PUT /admin/assets/1`, `POST /admin/assets/1/retire`, `GET /admin/incidents`, `POST /admin/incidents/1/resolve`, `GET /admin/reservations`, `POST /admin/reservations/1/check-out`, `POST /admin/reservations/1/return`, `POST /admin/reservations/jobs/run-lifecycle`, `GET /admin/reassignments`. They come from `RequestMappingHandlerMapping.getHandlerMethods()` (the bean named `requestMappingHandlerMapping`, the one holding our controllers). The test first checks that two known endpoints are in the list, so it can't pass by finding nothing. Soft assertions list every open endpoint in one run.

**Error cases checked:**

| Case | Status + code |
|---|---|
| Unknown path `/api/v1/does-not-exist` | 404 `NOT_FOUND` |
| `GET /api/v1/assets/999999999` | 404 `ASSET_NOT_FOUND` |
| `GET /api/v1/reservations/abc` | 400 `INVALID_PARAMETER` |
| `POST /api/v1/reservations` with `{not json` | 400 `MALFORMED_REQUEST` |
| `POST /api/v1/reservations` with `{}` | 400 `VALIDATION_FAILED` |
| B books A's slot | 409 `RESERVATION_CONFLICT` |
| `DELETE /api/v1/reservations/{id}` | 405 `METHOD_NOT_ALLOWED` |
| `GET /api/v1/users/me` without a token | 401 `UNAUTHORIZED` |
| Student on `GET /api/v1/admin/reservations` | 403 `FORBIDDEN` |

Leak markers (lower case): `trace`, `exception`, `com.campus`, `org.springframework`, `java.`, `hibernate`, `jdbc`, `sql`, `insert into`, `select `, `constraint`.

### How it connects to teammates' modules

| Teammate | What the test relies on |
|---|---|
| Calin | `ReservationService.loadOwnedOrAdmin` (404 for others), booking rules, `/me` lists, shared `Clock` |
| Liviu | Admin catalog endpoints for the setup; `GlobalExceptionHandler` for every error body and the generic 500 |
| Stefania | `CurrentUser` (owner from the token); `POST /api/v1/incidents` ignores `reportedById` |
| Matei | `SecurityConfig` admin rule + `SecurityErrorHandler` (401/403 JSON), login tokens |

Only files in `com.campus.config` (tests) and my docs changed. When Stefania's #108 merges (`GET /api/v1/incidents/{id}`, `/incidents/me`), add the same B-gets-404 check for incidents. Disabled users are covered by #128, not here.

### How it was tested

| Check | Result |
|---|---|
| `./gradlew spotlessApply build --rerun` (scratch PostgreSQL 18 on :5440) | BUILD SUCCESSFUL, 362 tests (355 + 7) |
| Removed the owner check in `ReservationService.loadOwnedOrAdmin` (local only, reverted) | The 2 IDOR tests fail: B gets 200 and can cancel A's booking |
| Narrowed `/api/v1/admin/**` to `/api/v1/admin/reservations/**` in `SecurityConfig` (reverted) | Admin test fails and lists all 12 endpoints left open (e.g. student on `GET /api/v1/admin/reassignments` → 200) |
| Generic 500 returned `ex.getMessage()` in `GlobalExceptionHandler` (reverted) | 500 test fails: `insert into`, `constraint` found |

### Bugs found

None on `main` (`8f38b68`). Every endpoint answered as the contract says.

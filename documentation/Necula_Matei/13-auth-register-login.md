# Register and login (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-26
**Branch / PR:** `feat/auth-register-login`
**See also:** `04-password-jwt-roles.md` (hashing, token signing, roles), `08-auth-request-records.md` (the request/response records), `docs/api-contract.md` §3

---

## Part 1: In simple terms

Until now the app had a locked door (security) but no way to get a key. This PR adds the counter that hands out keys.

1. **Sign up:** a student sends email, password and name. The app refuses an email that already has an account, stores only a scrambled version of the password, and always creates a *student*, so nobody can make themselves an admin.
2. **Log in:** the app checks the email and password. If both are right, it hands out an **entry ticket** (a token) valid for 8 hours.
3. **Same answer for every mistake:** wrong email, wrong password or a blocked account all get "wrong email or password", so nobody can find out which emails are registered.
4. **The ticket opens the right doors:** a student's ticket works on normal pages, but admin pages stay closed.
5. **Tested it** without the database (fast checks of every rule) and on the real app (sign up → log in → use the ticket).

**Result:** teammates can log in from Swagger (Authorize button) and try their endpoints with a real token. Stefania's `CurrentUser` and the frontend read the ticket's contents.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/service/AuthService.java` | New: `register` and `login`, token creation |
| `user/web/RegisterRequest.java` | Bug fix: passwords over 72 **bytes** are a 400, not a 500 (see below) |
| `user/web/AuthController.java` | `POST /api/v1/auth/register` (201) and `POST /api/v1/auth/login` (200), Swagger `@Tag`/`@Operation` |
| `test/.../user/service/AuthServiceTest.java` | New: 7 Mockito tests with the real encoders |
| `test/.../user/web/AuthControllerTest.java` | `@MockitoBean AuthService` + 4 new web tests |
| `test/.../user/web/AuthFlowIT.java` | New: 2 end-to-end tests on `campus_test` (`...IT` = needs the whole app, AGENTS.md §5) |
| `test/.../user/web/AuthRequestsTest.java` | +1 test for the byte limit |
| `02-files-explained.md` | Rows for the new files |

### Key choices

| Choice | Why |
|---|---|
| Role always `STUDENT` on register | `RegisterRequest` has no role field, and the service sets it. Admins come from the seeder (and later Stefania's user admin) |
| `existsByEmailIgnoreCase` first, then `saveAndFlush` in a `try` | The check gives a clean `409 EMAIL_TAKEN`. Two simultaneous registrations can both pass it, so the `UNIQUE` column is the real guard: `saveAndFlush` sends the INSERT immediately, so the duplicate error happens inside the `try` and becomes the same 409 instead of a 500 |
| One `orElseThrow` for unknown email, disabled user and wrong password | Contract §3: the same `401 INVALID_CREDENTIALS`, so the API doesn't reveal which emails exist. `filter(...)` turns each failed check into "empty" |
| `UnauthorizedException` / `ConflictException` from `common.error` | Liviu's `GlobalExceptionHandler` turns them into `ProblemDetail` JSON; the controller builds no error bodies (AGENTS.md §5) |
| Token claims `sub`, `email`, `roles`, `iat`, `exp` | Contract §3. `sub` = user id as a string (Stefania's `CurrentUser.id()` reads it), `roles` = `["STUDENT"]` or `["ADMIN"]` (my `SecurityConfig` turns it into `ROLE_...`), `exp` makes the decoder reject old tokens |
| Time from the shared `Clock` (Calin's #27) | `clock.instant()` for `iat`/`exp`, `LocalDateTime.now(clock)` for `created_at`; tests use a fixed clock |
| Lifetime from `app.jwt.expiration: 8h` as a `Duration` | Spring Boot converts `"8h"` itself. `expiresIn = 28800` seconds tells the frontend when to send the user back to login |
| Services return the web records (`UserResponse`, `LoginResponse`) | Mapping happens inside the `@Transactional` method (open-in-view is off), the entity never reaches the controller |
| `GET /api/v1/users/me` not in this PR | It needs the current user's id, which comes from Stefania's `CurrentUser` (still being agreed on #40). It follows once that's on `main` |

### Bug fixed on the way: long passwords gave a 500

| | |
|---|---|
| Found by | A review agent reading the code, then confirmed with a test |
| What was wrong | `@Size(max = 72)` counts **characters**, but BCrypt's limit is 72 **bytes**. In UTF-8, "ș" takes 2 bytes, so 40 × "ș" (80 bytes) passed validation, and `BCryptPasswordEncoder.encode()` threw `IllegalArgumentException: password cannot be more than 72 bytes` → 500 |
| Fix | `RegisterRequest.isPasswordWithinBcryptLimit()` with `@AssertTrue`: Hibernate Validator also checks getter-style methods (`isXxx()`) that carry a rule. Now it's `400 VALIDATION_FAILED`, field `passwordWithinBcryptLimit`. `@JsonIgnore` keeps the method out of the JSON and the Swagger schema (checked) |
| Login | Not affected: `matches()` with an over-long password simply returns false → the normal 401 |

### Tests

| Test class | What it proves |
|---|---|
| `AuthServiceTest` (7) | register: STUDENT + lowercase email + real `{bcrypt}` hash; taken email → `EMAIL_TAKEN` and nothing saved; duplicate on insert → `EMAIL_TAKEN`. login: token decoded with the same key, `sub`/`email`/`roles`/`iat`/`exp` exact (`JwtTimestampValidator` on the fixed clock), `expiresIn` 28800; wrong password, unknown email and disabled user → `INVALID_CREDENTIALS` |
| `AuthControllerTest` (+4) | register 201 without a `passwordHash` field; bad email + 5-character password → 400 `VALIDATION_FAILED` with 2 field errors (works since #52); login 200 JSON shape; wrong password → 401 `INVALID_CREDENTIALS` |
| `AuthRequestsTest` (+1) | 40 × "ș" (80 bytes) → only `passwordWithinBcryptLimit` fails; 36 × "ș" (exactly 72 bytes) passes |
| `AuthFlowIT` (2) | Real app: register → login (email typed in capitals) → the token passes security (404 on an unknown path) and gets 403 on `/api/v1/admin/**`; same email with other capitals → 409 |

`./gradlew spotlessApply build`: **BUILD SUCCESSFUL, 51 tests** (37 before + 14 new).

Not changed (from the review): the email lookups use `...IgnoreCase` (`upper(email) = upper(?)`), which can't use the UNIQUE index. With a few hundred users that costs nothing, and emails are stored lowercase anyway, so it can become `findByEmail` later if it ever matters.

### How teammates use it

1. Run the app, open Swagger, `POST /api/v1/auth/login`.
2. Copy `accessToken`, click **Authorize**, paste it.
3. Every request now sends `Authorization: Bearer <token>`. Dev accounts come with the `UserSeeder` PR.

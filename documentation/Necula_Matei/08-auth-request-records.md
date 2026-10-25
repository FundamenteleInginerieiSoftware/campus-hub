# Register and login messages (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-21
**Branch / PR:** `feat/auth-records`
**See also:** `docs/api-contract.md` §3 (auth rules), `04-password-jwt-roles.md` (tokens)

---

## Part 1: In simple terms

Before the register and login buttons can work, we fix the exact shape of what the website sends and what it gets back.

1. **The sign-up form:** e-mail, password, full name, with rules checked automatically: a real e-mail address, a password of 8 to 72 characters, a name of at most 100 characters.
2. **No "role" on the sign-up form.** Everyone who signs up is a student, so nobody can make themselves an admin.
3. **The login form:** e-mail and password, only "not empty". Any wrong login gets the same answer, so it doesn't hint whether the e-mail exists.
4. **The answers:** the user's details (never the password), and after login the entry ticket plus how long it lasts.
5. **Tested every rule.**

**Result:** the register and login endpoints only have to be connected to these shapes.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/web/RegisterRequest.java`, `LoginRequest.java` | New: request bodies with Jakarta validation |
| `user/web/UserResponse.java`, `LoginResponse.java` | New: response bodies (`UserResponse.from(User)` mapper) |
| `test/.../user/web/AuthRequestsTest.java` | New: 5 plain unit tests for the validation rules and the mapper |

### Key choices

| Choice | Why |
|---|---|
| Validation on the records (`@NotBlank`, `@Email`, `@Size`) | Rules from contract §3: e-mail ≤ 255, password 8–72, full name ≤ 100. With `@Valid` in the controller, a broken rule becomes `400 VALIDATION_FAILED` (Liviu's handler) before our code runs |
| `RegisterRequest` has no `role` | Register always creates a `STUDENT`; nobody can send `"role":"ADMIN"` |
| `LoginRequest` only `@NotBlank` | Any wrong e-mail/password gets the same `401 INVALID_CREDENTIALS`, so login doesn't reveal which rule failed |
| `UserResponse.from(User)` is `public static` | `AuthService` (in `user/service`, another package) calls it inside its `@Transactional` method, so the entity never reaches the controller |
| `LoginResponse.expiresIn` in seconds | 28800 = 8 h (`app.jwt.expiration`); the frontend knows when to send the user back to login |

### Testing

| Command / test | Result |
|---|---|
| `cd backend && ./gradlew spotlessApply build` | BUILD SUCCESSFUL, **29 tests** (+5) |
| `AuthRequestsTest` | Valid register passes; bad e-mail + short password + blank name → exactly those 3 fields; 73-char password fails; login needs only both fields; `UserResponse` has the lowercased e-mail and no hash |

`AuthRequestsTest` is a plain unit test (no Spring): `Validation.buildDefaultValidatorFactory()` checks the annotations directly, so it runs in milliseconds.

### Known limitation

| Problem | Status |
|---|---|
| `@Size(max = 72)` counts **characters**, BCrypt's limit is 72 **bytes** | Not fixed yet, noted in `RegisterRequest`: letters like `ș` take 2 bytes, so a long password full of them could still be too long for BCrypt (500 at register). The rule is 🟡 in the contract; decide when `AuthService` is written |

### Next

| Step | Waits for |
|---|---|
| `AuthService` + `POST /register`, `POST /login`, `GET /users/me` | Calin's `Clock` (token `iat`/`exp`, `createdAt`), PR #27. Liviu's `ConflictException` (`409 EMAIL_TAKEN`) and `UnauthorizedException` (`401 INVALID_CREDENTIALS`) are on `main` since #22 |
| `UserSeeder` | Calin's `Clock` (PR #27) |

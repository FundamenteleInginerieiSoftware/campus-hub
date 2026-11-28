# Self-service password change: PUT /api/v1/users/me/password (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-28
**Branch / PR:** `feat/user-change-password` (#379)
**See also:** `13-auth-register-login.md` (register/login + JWT), `17-users-me.md` (`GET /api/v1/users/me`), `47-login-hardening.md` (72-byte BCrypt rule), `docs/api-contract.md` §3

---

## Part 1: In simple terms

Until now, a user could register and log in, but had no way to change their own password (for example, to replace an initial account password or rotate a personal one).

1. **What was built:** a new logged-in endpoint, `PUT /api/v1/users/me/password`. The user sends their current password and a new password, and the server updates the stored hash and returns `204 No Content`.
2. **Only your own account:** the request has no user ID field. The server reads who you are directly from your login token, so nobody can change another user's password.
3. **Current password checked first:** before checking whether the new password repeats the old one, the server verifies the current password. If it is wrong (or longer than BCrypt's 72-byte limit), the call fails with `401 INVALID_CREDENTIALS`. Checking in this order stops anyone from probing guesses by looking for the "password unchanged" error.
4. **New password rules and audit trail:** if the current password matches, the server refuses reusing the same password (`409 PASSWORD_UNCHANGED`) and rejects new passwords outside 8–72 characters or over 72 UTF-8 bytes (`400 VALIDATION_FAILED`). Spaces are kept as typed, passwords are never logged, and a `USER_PASSWORD_CHANGED` entry is recorded in the audit log with only `"Password changed"` as details.

**Result:** any logged-in student or admin can safely change their own password, the old password stops working on login immediately, no database migration is needed, and Stefania can wire the frontend Change Password UI directly against `PUT /api/v1/users/me/password`.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/src/main/java/com/campus/user/domain/User.java` | Added `changePassword(String encodedPasswordHash)` domain method (keeps `User` free of generic setters) |
| `backend/src/main/java/com/campus/user/web/ChangePasswordRequest.java` | **New.** Request record with `@NotBlank currentPassword`, `@NotBlank @Size(min = 8, max = 72) newPassword`, and `@JsonIgnore @AssertTrue isNewPasswordWithinBcryptLimit()` (72 UTF-8 bytes); neither field is trimmed |
| `backend/src/main/java/com/campus/user/service/UserService.java` | Injected `PasswordEncoder` and `AuditService`; added `@Transactional changePassword(ChangePasswordRequest)` and `fitsBcrypt(String)` |
| `backend/src/main/java/com/campus/user/web/UserController.java` | Added `PUT /api/v1/users/me/password` (`@ResponseStatus(HttpStatus.NO_CONTENT)`, `@Valid @RequestBody`, OpenAPI 204/400/401/409 docs) |
| `backend/src/test/java/com/campus/user/service/UserServiceTest.java` | +7 Mockito tests (14 total): happy path + audit row, wrong current password, >72-byte `currentPassword` (skips BCrypt), exact 72-byte `currentPassword`, same-password reuse (`409`), check-order oracle guard (wrong current == new → `401`), missing user (`404`) |
| `backend/src/test/java/com/campus/user/web/UserControllerTest.java` | +9 `@WebMvcTest` + `@Import(SecurityConfig.class)` slice tests (11 total): 204 valid, spaces preserved, 401 without token, 400 blank/short/>72-byte `newPassword`, 204 exact 72-byte `newPassword`, 401 `INVALID_CREDENTIALS` and 409 `PASSWORD_UNCHANGED` propagation |
| `backend/src/test/java/com/campus/user/web/ChangePasswordIT.java` | **New.** 4 `@SpringBootTest` + `@Transactional` integration tests against PostgreSQL 18 (`campus_test`): full change flow (old login 401, new login 200 + JWT, 1 `USER_PASSWORD_CHANGED` audit row with no password/`{bcrypt}` leak), wrong current password (401, old login still works, 0 audit rows), reuse (409 `PASSWORD_UNCHANGED`, 0 audit rows), >72-byte `currentPassword` (401 `INVALID_CREDENTIALS`, 0 audit rows) |
| `docs/api-contract.md` | Added `PUT /api/v1/users/me/password` in §3 and `PASSWORD_UNCHANGED` (`409`, Matei) in the error-codes table |
| `documentation/Necula_Matei/{70-change-password-endpoint.md,02-files-explained.md,README.md}`, `docs/whats-left.md`, `AGENTS.md` | End-of-task documentation and status lines |

### Key code and security choices

```java
@Transactional
public void changePassword(ChangePasswordRequest request) {
    Long id = currentUser.id();
    User user = userRepository
            .findById(id)
            .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User " + id + " not found"));

    if (!fitsBcrypt(request.currentPassword())
            || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
        throw new UnauthorizedException("INVALID_CREDENTIALS", "Current password is incorrect");
    }

    if (request.newPassword().equals(request.currentPassword())) {
        throw new BusinessRuleException(
                "PASSWORD_UNCHANGED", "New password must be different from the current password");
    }

    user.changePassword(passwordEncoder.encode(request.newPassword()));
    auditService.record(id, "USER_PASSWORD_CHANGED", "USER", id, "Password changed");
}
```

| Choice | Why |
|---|---|
| Target user from `CurrentUser.id()`, never the request body | Reads the JWT `sub` claim in the service layer (AGENTS.md §5). `SecurityConfig`'s `/api/v1/**` rule already rejects missing, invalid, disabled, or deleted accounts with `401 UNAUTHORIZED` before the controller runs. |
| Current-password check **before** reuse check | If `newPassword.equals(currentPassword)` ran first, sending the same string in both fields without knowing the real password would answer `409 PASSWORD_UNCHANGED` instead of `401 INVALID_CREDENTIALS`, turning `409` into a password-guessing oracle. |
| 72-byte UTF-8 check on `currentPassword` in `UserService`, not `@Size` on the record | Matches `AuthService.login` (`47-login-hardening.md`): BCrypt ignores bytes past 72, so `fitsBcrypt` returns `401 INVALID_CREDENTIALS` without calling `PasswordEncoder` and without exposing validation rules via `400`. |
| `@Size(min = 8, max = 72)` + `@AssertTrue` UTF-8 byte check on `newPassword` | `@Size` counts Java characters, so 37 × `"ș"` (74 UTF-8 bytes) passes `@Size` and would fail inside `BCrypt.encode()`. `@JsonIgnore @AssertTrue isNewPasswordWithinBcryptLimit()` turns it into `400 VALIDATION_FAILED` while staying hidden from JSON and Swagger (same as `RegisterRequest`). |
| Passwords never trimmed and never logged | Spaces are valid characters in passwords (consistent with `RegisterRequest`, verified in `UserControllerTest`). No log statement prints either password or hash. |
| Audit write inside the same `@Transactional` method | `auditService.record(id, "USER_PASSWORD_CHANGED", "USER", id, "Password changed")` commits or rolls back atomically with `user.changePassword(...)`. `details` is always the fixed text `"Password changed"` so admins reading `audit_logs` never see passwords or hashes. |
| No Flyway migration | `users.password_hash` and `audit_logs` already exist on `main`. |

### How it connects to teammates' modules

- **Stefania (`common.security.CurrentUser`, frontend):** `UserService.changePassword` reads the caller's ID from Stefania's `CurrentUser.id()`. On the frontend, Stefania will wire the Change Password UI against `PUT /api/v1/users/me/password` using the contract in `docs/api-contract.md` §3 (`204`, `400 VALIDATION_FAILED`, `401 INVALID_CREDENTIALS`, `409 PASSWORD_UNCHANGED`).
- **Liviu (`common.audit.AuditService`, `common.error`):** writes the `USER_PASSWORD_CHANGED` (`USER`) audit entry via Liviu's `AuditService` in the same transaction, and throws Liviu's `NotFoundException`, `UnauthorizedException`, and `BusinessRuleException` so `GlobalExceptionHandler` produces RFC 9457 `ProblemDetail` JSON with `code`.
- **Calin (ArchUnit tests):** verified by `CodingConventionsArchitectureTest` and `ModuleBoundaryArchitectureTest` — `user` only depends on `common.*`, keeps `@Transactional` on public service methods, and exposes records (never `@Entity`) in `web/`.

### Verification

Command (against scratch PostgreSQL 18):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/campus_test ./gradlew spotlessApply build --no-daemon
```

Result: **`BUILD SUCCESSFUL in 1m 29s`** — **130 test suites, 1,420 tests, 0 failures, 0 errors, 0 skipped**.

| Suite | Tests | What it covers here |
|---|---:|---|
| `UserServiceTest` | 14 | Service rules: hash update, audit call, wrong current password, 72-byte boundary, reuse `409`, oracle-safe check order, missing user `404` |
| `UserControllerTest` | 11 | HTTP contract & validation: `204`, spaces kept, `401` without token, `400` blank/short/>72-byte `newPassword`, exact 72-byte `newPassword`, `401`/`409` propagation |
| `ChangePasswordIT` | 4 | Full Spring Boot + PostgreSQL 18 flow: real JWT + BCrypt, old login rejected, new login works, `USER_PASSWORD_CHANGED` audit row persisted without secrets, failure paths leave state and audit untouched |
| `AuthFlowIT` | 6 | Existing register / login / `/users/me` end-to-end flow stays green |
| `SecurityPassIT` | 7 | IDOR, admin-only prefix, and `ProblemDetail` error hygiene stay green |
| `DemoFlowIT` | 10 | End-to-end demo flow stays green |
| `CodingConventionsArchitectureTest` | 21 | Calin's coding conventions (DTO records, `@Transactional`, Boot 4 imports, etc.) |
| `ModuleBoundaryArchitectureTest` | 7 | Calin's module-boundary rules (`user` imports only allowed `common.*` packages) |

### Problems hit and fixes

No build or runtime failures were hit during implementation or verification. Two security edge cases were handled up front and locked in with dedicated tests:
- **Oracle risk on `409 PASSWORD_UNCHANGED`:** checking `fitsBcrypt` + `passwordEncoder.matches` before `newPassword.equals(currentPassword)` (tested by `UserServiceTest.changePasswordChecksCurrentPasswordBeforeRejectingReuse`).
- **Multi-byte UTF-8 passwords vs. `@Size`:** adding `@AssertTrue isNewPasswordWithinBcryptLimit()` on `ChangePasswordRequest` and `fitsBcrypt` in `UserService` so 37 × `"ș"` (74 UTF-8 bytes) returns `400` as `newPassword` and `401` as `currentPassword`, never silently truncating or throwing `500`.

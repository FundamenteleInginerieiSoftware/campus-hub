# Register accepts spaces around the email (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-05
**Branch / PR:** `fix/user-register-trim-email` (issue #165, reported by Calin)
**See also:** `08-auth-request-records.md` (the request records), `13-auth-register-login.md` (register and login), `docs/api-contract.md` §3

---

## Part 1: In simple terms

1. **The problem:** a phone keyboard or a copy-paste often adds a space before or after the email. Sign-up then failed with "must be a well-formed email address", even though the email itself was fine.
2. **Why:** the app checked the email's format first and only removed the spaces later. With the spaces still there, the check said no, so the "remove spaces" step was never reached.
3. **The fix:** the spaces around the email and the name are now removed the moment the request arrives, before any check runs. The password is left exactly as typed, because a space can be part of it.
4. **Login already worked** with spaces: it has no format check and removes the spaces itself.

**Result:** `" Ana@Campus.test "` signs up as `ana@campus.test`, and the student can log in with it.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/web/RegisterRequest.java` | Compact constructor trims `email` and `fullName` (null-safe) |
| `user/service/AuthService.java` | `register()`: dropped the now useless `.trim()` before `existsByEmailIgnoreCase` |
| `test/.../user/web/AuthFlowIT.java` | `registerTrimsSpacesAroundTheEmail()`: register `" Spaced@Campus.test "` → 201, `email` = `spaced@campus.test`, then login works |
| `test/.../user/web/AuthRequestsTest.java` | `trimsEmailAndNameButNotThePassword()`: no Spring, checks the record and the validator |

### The fix

```java
public RegisterRequest {
    email = email == null ? null : email.trim();
    fullName = fullName == null ? null : fullName.trim();
}
```

| Choice | Why |
|---|---|
| Trim in the record's compact constructor | Jackson creates the record through its constructor; `@Valid` checks the object after that, so `@Email`, `@NotBlank` and `@Size` see the trimmed value |
| `null` left as `null` | A missing field must still give 400 from `@NotBlank`, not a 500 from `trim()` |
| Password not trimmed | Spaces are valid password characters; the 72-byte `@AssertTrue` check is unchanged and its tests still pass |
| `User` constructor keeps its `trim().toLowerCase()` | `UserSeeder` and `AdminBootstrap` create users without a `RegisterRequest`, so the entity still normalizes on its own. Lowercasing stays there, in one place |
| `LoginRequest` unchanged | Only `@NotBlank`, no `@Email`, and `AuthService.login` already trims before the lookup |
| `docs/api-contract.md` unchanged | §3 already says "trimmed, stored lowercase"; the code now matches it |

### Effect on others

| Who | Effect |
|---|---|
| Stefania (register page, PR #107) | No change needed: the backend now accepts the email as typed. Trimming in the form too is optional |
| Calin (reporter, #165) | Closed by this PR |

### Tests

| Check | Result |
|---|---|
| The 2 new tests **without** the fix | Both failed: 400 instead of 201, and `" ana@campus.test "` not trimmed |
| `./gradlew spotlessApply build --rerun` with the fix | BUILD SUCCESSFUL, 380 tests |

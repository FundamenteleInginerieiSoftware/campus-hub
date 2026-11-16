# Login hardening: long passwords and database connection (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-10
**Branch / PR:** `fix/auth-login-hardening` (from the 10 Nov audit)
**See also:** `13-auth-register-login.md`, `28-register-trim-email.md`, `docs/report/security.md`

---

## Part 1: In simple terms

1. **Problem 1, long passwords:** the password scrambler (BCrypt) only reads the first 72 bytes of a password. At sign-up, longer passwords are refused. At login, nothing checked: a long password was either cut silently or could crash the server.
2. **Why it matters:** if someone's real password is 72 characters long, typing it plus anything extra at the end would have logged them in.
3. **Fix 1:** at login, a password longer than 72 bytes now gets the same "wrong email or password" answer (401) as any wrong password. The scrambler is not even called.
4. **Problem 2, busy connection:** login kept a database connection open while the slow password check ran. With many logins at once, connections run out for everybody else.
5. **Fix 2:** login no longer holds a connection during the check. It only needs one short database read, which opens and closes its own connection.

**Result:** long passwords never log in and never crash; login frees the database connection sooner.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/service/AuthService.java` | `login()`: `@Transactional(readOnly = true)` removed; `fitsBcrypt()` (72 UTF-8 bytes, same rule as `RegisterRequest`) checked before `passwordEncoder.matches`; bug-fix comments |
| `test/.../user/service/AuthServiceTest.java` | `loginRejectsPasswordOver72BytesWithTheSameError`, `loginRejectsRealPasswordWithExtraBytesAfter72` |
| `test/.../user/web/AuthFlowIT.java` | `loginWithPasswordOver72BytesIsA401` (whole app, checks status and `code`) |
| `docs/report/security.md` | Login row mentions the 72-byte rule |

### Choices

| Choice | Why |
|---|---|
| No `@Size(max = 72)` on `LoginRequest` | `@Size` counts characters, not bytes ("ș" is 2 bytes), and a 400 would show which rule failed; login must always answer 401 |
| Byte count, not length | Same idea as `RegisterRequest.isPasswordWithinBcryptLimit` |
| `@Transactional` removed | `login` only reads via `findByEmailIgnoreCase` (own read-only transaction); `User` has no lazy associations and `UserResponse.from` reads plain columns; nothing is written |

### Tests: before and after

| Test | Without the fix | With the fix |
|---|---|---|
| Password of 37 x "ș" (74 bytes) against a different password | Passes (BCrypt just says no; no 500 on this Spring Security version), kept as a guard | Passes |
| Real 72-byte password + `"extra"` | **Fails**: login succeeded (`AssertionError`, no `UnauthorizedException`), BCrypt cut the tail | Passes, 401 `INVALID_CREDENTIALS` |
| IT: 73-byte password over HTTP | Passes (guard) | Passes |

So the real bug on this version was silent truncation (wrong accept), not a 500.

Command: `cd backend && ./gradlew --no-daemon spotlessApply build` → BUILD SUCCESSFUL, 991 tests, 0 failures.

### Not changed: rate limiting

`docs/report/security.md` already lists "No rate limiting and no account lockout on login" in the known limits, with the mitigation (WAF/CloudFront, lockout). Nothing to add.

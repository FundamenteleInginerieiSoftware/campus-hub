# A token with a bad user id gets 401, not 500 (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-05
**Branch / PR:** `fix/security-jwt-sub-401` (issue #158, found by Calin)
**See also:** `22-token-account-check.md` (the account check this fixes), `04-password-jwt-roles.md` (tokens)

---

## Part 1: In simple terms

1. **Every login pass (token) carries the user's number** in a field called `sub`. Since #128 the server reads that number on every request to look the account up.
2. **The problem:** if the pass had no number, or text instead of a number (an old pass format, or a mistake of ours), the server crashed on reading it and answered "server error" (500).
3. **The fix:** such a pass is now treated like any other bad pass: "log in first" (401), with the same error format as the other 401 answers.
4. **Normal passes change nothing:** our login always writes the user's number there.

**Result:** a broken pass can no longer cause a server error; the frontend sees a 401 and sends the user back to the login page.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `config/SecurityConfig.java` | New `userId(Jwt)`: parses `sub`, throws `InvalidBearerTokenException` if it is missing or not a number; `activeUserFromToken` uses it |
| `test/.../config/SecurityConfigTest.java` | 2 new tests (`sub` = `"abc"`, no `sub`); new helpers `bearerWithSubject(...)` + `sign(...)` |

### Why it happened

| Step | What happens |
|---|---|
| 1 | The signature and expiry checks pass (the token is signed with our secret) |
| 2 | `activeUserFromToken` calls `Long.valueOf(jwt.getSubject())` |
| 3 | `"abc"` or `null` → `NumberFormatException` (`Long.valueOf(null)` throws it too) |
| 4 | It is not an `AuthenticationException`, so the security filter doesn't turn it into a 401: the request ends in 500 |

### The fix

```java
private static Long userId(Jwt jwt) {
    try {
        return Long.valueOf(jwt.getSubject());
    } catch (NumberFormatException e) { // also covers a missing "sub"
        throw new InvalidBearerTokenException("The token does not name a user");
    }
}
```

| Choice | Why |
|---|---|
| `InvalidBearerTokenException` | Same exception as the disabled/deleted-account case (#117). It is an `AuthenticationException`, so Spring sends it to our `SecurityErrorHandler`: 401 ProblemDetail with `code: UNAUTHORIZED` |
| One `catch`, no separate `null` check | `Long.valueOf(null)` already throws `NumberFormatException`; the comment says so |
| Small private method | Keeps the converter readable; nothing else in the file changes |

### How it connects

| Who | Effect |
|---|---|
| Stefania's API client | A 401 already logs the user out and opens the login page; a 500 didn't |
| `CurrentUser` (Stefania) | Reads the same `sub`; it can now never see a non-numeric one, because such tokens stop here |
| Real attackers | Can't make such a token without the HS256 secret; this protects against our own bugs and old token formats |

### Tests

| Check | Result |
|---|---|
| `./gradlew test --tests 'com.campus.config.SecurityConfigTest' --rerun` **without** the fix | 2 failed (`NumberFormatException`) |
| Same command with the fix | 18 tests, all green |
| `./gradlew spotlessApply build --rerun` (scratch PostgreSQL on :5433) | BUILD SUCCESSFUL, 380 tests |

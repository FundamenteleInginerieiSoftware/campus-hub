# Login works even with an old token (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-04
**Branch / PR:** `fix/security-public-auth-token` (issue #116)
**See also:** `04-password-jwt-roles.md` (security rules), `13-auth-register-login.md`

---

## Part 1: In simple terms

1. **The problem:** the website keeps the login "pass" (token) in the browser and sends it with every request, also with the login request itself.
2. **After 8 hours the pass expires.** The server saw the expired pass on the login request and answered "log in first", so the login that would give a new pass failed.
3. **The fix:** on the login, register and ping addresses the server now ignores the pass completely. They are open to everyone anyway.
4. **Everywhere else nothing changes:** an expired or fake pass is still refused.

**Result:** a student whose session expired can simply log in again on the first try.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `config/SecurityConfig.java` | `oauth2ResourceServer(...).bearerTokenResolver(ignoreTokenOnAuthPaths())` |
| `test/.../config/SecurityConfigTest.java` | `authEndpointsIgnoreAnInvalidToken`: `GET /api/v1/auth/ping` with a broken token → 200 |
| `test/.../user/web/AuthFlowIT.java` | `loginWorksWhileAnExpiredTokenIsStillSent`: login with an old `Authorization` header → 200 + new token |

### Why it happened

| Step | What Spring does |
|---|---|
| 1 | `BearerTokenAuthenticationFilter` runs **before** `authorizeHttpRequests` |
| 2 | It reads any `Authorization: Bearer ...` header and validates it |
| 3 | Invalid → our entry point answers 401 at once; the `permitAll()` rule for `/api/v1/auth/**` is never reached |

Stefania's `apiFetch` (`frontend/src/api/client.ts`) sends the saved token on every call, so an expired one hit step 3.

### The fix

```java
RequestMatcher authPaths = PathPatternRequestMatcher.withDefaults().matcher("/api/v1/auth/**");
DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
return request -> authPaths.matches(request) ? null : header.resolve(request);
```

A `BearerTokenResolver` tells the filter where the token is. Returning `null` means "no token in this request", so the filter does nothing and the `permitAll()` rule applies. On all other paths `DefaultBearerTokenResolver` reads the header as before.

| Choice | Why |
|---|---|
| Fix on the backend, not in the frontend | Any client (Swagger, curl, a future app) could send an old token; the server should not depend on clients being careful |
| `PathPatternRequestMatcher`, not `startsWith` | Same matching rules as the `requestMatchers(...)` lines in the same file |

### Tests

| Check | Result |
|---|---|
| Both new tests **without** the fix | 2 failed (401) |
| With the fix: `./gradlew test --tests 'com.campus.config.*' --tests 'com.campus.user.*'` | green |

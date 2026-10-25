# 401 and 403 as clear JSON errors (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-21
**Branch / PR:** `feat/auth-error-json`
**See also:** `04-password-jwt-roles.md` (the security rules), `docs/api-contract.md` §4 (error format)

---

## Part 1: In simple terms

When the guard at the door refuses someone, it used to answer with an empty message. The website then can't tell what went wrong.

1. **"Log in first" (401) now comes with a clear message** and the code `UNAUTHORIZED`: no ticket, a fake one, or an expired one.
2. **"Not allowed" (403) now comes with a clear message** and the code `FORBIDDEN`: for example, a student opening an admin page.
3. **Same format as every other error in the app**, so the website handles all errors the same way.
4. **Tested it** for a missing ticket, a fake ticket and a student on an admin page.

**Result:** the website can react to the code: on `UNAUTHORIZED` it forgets the old ticket and shows the login page.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `config/SecurityErrorHandler.java` | New: writes 401/403 as `ProblemDetail` JSON (`UNAUTHORIZED`, `FORBIDDEN`) |
| `config/SecurityConfig.java` | Wires the handler into the resource server and `exceptionHandling` |
| `test/.../config/SecurityConfigTest.java` | 401/403 tests also check the JSON body |

### Key choices

| Choice | Why |
|---|---|
| A handler of our own for 401/403 | Errors thrown in the security filters never reach Liviu's `GlobalExceptionHandler` (`@RestControllerAdvice`, on `main` since #22), so without it the body is empty. It writes the same shape as his handler (`type`, `title`, `status`, `detail`, `instance`, `code`; contract §4) with content type `application/problem+json` |
| One class for both | `SecurityErrorHandler` implements `AuthenticationEntryPoint` (401) and `AccessDeniedHandler` (403); both share one `write()` method |
| Wired twice: `oauth2ResourceServer(...).authenticationEntryPoint` and `exceptionHandling(...)` | The resource server answers an **invalid/expired** token itself; `exceptionHandling` covers **no token** and **403**. Without the first one, a fake token would still get an empty 401 |
| Built with `new` inside `SecurityConfig`, not a `@Component` | `@WebMvcTest` only loads web classes, so a separate bean would force every teammate's controller test to import it too. Now `@Import(SecurityConfig.class)` stays enough |
| Plain `LinkedHashMap` body + Boot's Jackson 3 `JsonMapper` | Simple and predictable; keeps the contract's field order |

Example (no token):
```json
{"type":"about:blank","title":"Unauthorized","status":401,
 "detail":"Log in first: the token is missing, invalid or expired",
 "instance":"/api/v1/assets","code":"UNAUTHORIZED"}
```

### How it connects to teammates

| Who | What they get |
|---|---|
| Liviu | 401/403 use the same `ProblemDetail` + `code` shape as his `GlobalExceptionHandler` (contract §4). If he changes the shape, `SecurityErrorHandler.write()` changes with it |
| Stefania | Frontend: on `401` / `code = UNAUTHORIZED`, drop the token and go to login |

### Testing

| Command / test | Result |
|---|---|
| `cd backend && ./gradlew spotlessApply build` | BUILD SUCCESSFUL, **23 tests** (same tests, stronger checks) |
| `otherEndpointsNeedAToken`, `invalidTokenIsRejected` | No token / fake token → **401**, `application/problem+json`, `code = UNAUTHORIZED`, `instance` = the path |
| `studentIsForbiddenOnAdminPaths` | Student token on `/api/v1/admin/assets` → **403**, `status = 403`, `code = FORBIDDEN` |
| `./gradlew bootRun` + `curl -i localhost:8080/api/v1/assets` | Real app (dev profile): 401 with the JSON above |

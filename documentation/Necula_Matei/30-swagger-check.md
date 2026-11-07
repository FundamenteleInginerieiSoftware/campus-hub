# Swagger check for M1 (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-06
**Branch / PR:** `fix/user-swagger-docs`
**See also:** `04-password-jwt-roles.md` (the Authorize button), `05-security-error-json.md` (401/403 JSON), `25-security-pass-tests.md` (admin endpoints really closed)

---

## Part 1: In simple terms

1. **M1 asks for a "complete Swagger":** the page where anyone can see and try every endpoint of our API. Each endpoint should say what it does, which answers it can give, and whether you must be logged in.
2. **I checked all 31 endpoints** of the 4 of us, in the page the running app really serves, not just in the code.
3. **What was wrong for everyone:** the login page itself showed a lock (as if you needed to be logged in to log in); most endpoints didn't say "401: log in first" or "403: admins only"; and when someone listed an error (e.g. "409 conflict"), Swagger claimed the error answer looks like a normal booking.
4. **The fix, in one place in my settings:** Swagger now copies what our security really does. Login, register and ping have no lock; every other endpoint lists 401; admin endpoints also list 403; every error shows our standard error format with its `code`. Teammates don't have to write any of that.
5. **My own endpoints** (register, login, "who am I") now list their real answers, and the login form is pre-filled with the dev test account, so "Try it out" works in one click.
6. **A test reads the Swagger page and checks every endpoint**, so a teammate's new endpoint without a description fails the build.
7. **What's left for teammates:** listing their own business errors (e.g. "404 asset not found"), one line each. I wrote the list per person (see "Findings").

**Result:** Swagger matches the real security rules for all 31 endpoints, and a test keeps it that way.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `config/OpenApiConfig.java` | New `OpenApiCustomizer` bean `securityAndErrorResponses()` + hand-written `ProblemDetail` schema |
| `user/web/AuthController.java` | `@ApiResponse` for register (201/400/409) and login (200/400/401); codes moved out of the summaries |
| `user/web/UserController.java` | `@ApiResponse` 200 for `/users/me` (401 comes from the customizer) |
| `user/web/LoginRequest.java` | `@Schema(example)`: `student1@campus.test` / `password` (dev seed) |
| `user/web/RegisterRequest.java` | `@Schema(example)` values that pass validation (Swagger's `"string"` fails `@Email` and min 8) |
| `resources/application.yml` | `springdoc.default-produces-media-type: application/json` (was `*/*`) |
| `docs/api-contract.md` §3 | `/users/me`: 401 for a disabled or deleted account, not 404 (since #128 the token check answers first) |
| `test/.../config/OpenApiDocsIT.java` | New: reads `/v3/api-docs`, checks every operation |

### What the customizer does

springdoc builds the spec from the annotations, then calls every `OpenApiCustomizer` bean once. Ours walks all paths:

| Path | Change | Why |
|---|---|---|
| `/api/v1/auth/**` | `operation.setSecurity(List.of())` | An empty list overrides the global `bearerAuth` from `@OpenAPIDefinition`: no lock icon. Mirrors `permitAll()` in `SecurityConfig` |
| every other path | `putIfAbsent("401", ...)` | `/api/v1/**` is `authenticated()` |
| `/api/v1/admin/**` | `putIfAbsent("403", ...)` | `hasRole("ADMIN")` |
| any 4xx/5xx | content = `application/problem+json` → `#/components/schemas/ProblemDetail` | springdoc puts the method's return type on **every** `@ApiResponse` without `content`, so Calin's 409 claimed to return a `ReservationResponse` |

| Choice | Why |
|---|---|
| `putIfAbsent` | Calin's hand-written 401/403 descriptions stay |
| Rebuilt in a `TreeMap` | `putIfAbsent` adds at the end; Swagger lists responses in the given order (200, 401, 403, 404 …) |
| `ProblemDetail` schema written by hand | Spring's `ProblemDetail` keeps `code` in a `properties` map, so springdoc would show a nested `properties` object instead of the flat JSON from api-contract §4 |
| Path prefixes, not reading `SecurityConfig` | Same three prefixes as `SecurityConfig`; `SecurityPassIT` already proves the real behaviour, this only documents it |

### Tests

| Check | Result |
|---|---|
| App on scratch PostgreSQL 18 (:5433), `curl /v3/api-docs`, Python script over all operations | Before: 31/31 with summary, 3 public ops with a lock, 22 without 401, 11 admin ops without 403, error bodies = success types, `*/*`. After: all fixed |
| Login and register with the Swagger examples | 200 and 201 |
| `OpenApiDocsIT` with the old `OpenApiConfig` + `application.yml` | Fails, 93 soft-assertion failures |
| `OpenApiDocsIT` with the fix | Green |
| `./gradlew spotlessApply build --rerun` (scratch PG) | BUILD SUCCESSFUL, 403 tests |

### Findings per teammate (to send as issues)

| Owner | Operations | Business codes missing in Swagger | Worst gap |
|---|---|---|---|
| Liviu (catalog) | 13 | 24 in 11 operations (no `@ApiResponse` at all) | 400/404/409 only in the summary text |
| Calin (reservation, reassignment) | 11 | 4 (availability 400 `INVALID_PARAMETER`, admin search / reassignment log 400, create 400 `MALFORMED_REQUEST`) | Swagger's example times (`…T13:26:06.320Z`) can never book: 409 `SLOT_NOT_ALIGNED`; needs `@Schema(example = "2026-11-10T14:00:00")` |
| Stefania (incident) | 3 | 7 (400/404/409 on report and resolve, 400 on the queue) | `GET /admin/incidents`: `Pageable` without `@ParameterObject` shows one required `pageable` object; "Try it out" sends `sort=string` → 400 |

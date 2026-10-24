# Passwords, tokens, roles and Swagger login (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-21
**Branch / PR:** `feat/auth-jwt-roles` (after #17)
**See also:** `03-user-entity.md` (the users table), `02-files-explained.md` (what each file does)

---

## Part 1: In simple terms

Login needs three tools: something to scramble passwords, something to hand out "entry tickets" (tokens), and a guard who reads the ticket and knows who is an admin. This PR builds those tools. The register and login buttons themselves come later.

1. **Passwords are scrambled before saving.** The app keeps only a scrambled version (a "hash"). Nobody, not even us, can read a password back; at login the typed password is scrambled the same way and compared.
2. **The app can now print entry tickets (tokens).** After login, the user gets a signed ticket that says "user 1, student". Only our server knows the signing secret, so a fake ticket is refused.
3. **The guard now knows about roles.** Addresses that start with `/api/v1/admin/` are for admins only: a student gets "forbidden". Every other `/api/v1/` address needs any valid ticket. Anything else is closed, so a forgotten rule can't leave a door open.
4. **Swagger got an "Authorize" button.** Paste a ticket there once, and Swagger sends it with every test call.
5. **Tested it** with real tickets for a student and an admin.

**Result:** teammates can put admin pages under `/api/v1/admin/` and they are protected automatically.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `config/SecurityConfig.java` | + `PasswordEncoder`, + `JwtEncoder`, roles converter, admin rule, `denyAll()` |
| `config/OpenApiConfig.java` | New: `bearerAuth` scheme for Swagger's Authorize button |
| `test/.../config/SecurityConfigTest.java` | + 6 tests (roles, deny-all, BCrypt, Swagger scheme) |

### Security rules (match `docs/api-contract.md` §2)

Checked top to bottom, **first match wins**:

| # | Path | Access |
|---|---|---|
| 1 | `/api/v1/auth/**` | public (ping, register, login) |
| 2 | health, Swagger, `/error` | public |
| 3 | `/api/v1/admin/**` | `hasRole("ADMIN")` |
| 4 | `/api/v1/**` | `authenticated()` |
| 5 | anything else | `denyAll()` |

Rule 3 must stay **above** rule 4: otherwise `/api/v1/**` would match admin paths first and let students in.

### Key choices

| Choice | Why |
|---|---|
| `PasswordEncoderFactories.createDelegatingPasswordEncoder()` | BCrypt today, stored as `{bcrypt}$2a$...`. The prefix names the algorithm, so we could move to a stronger one later and old hashes still work |
| HS256 (one shared secret) for both `JwtEncoder` and `JwtDecoder` | Only the backend signs and checks tokens, so a key pair (RS256) adds nothing. A small `secretKey()` helper builds the key for both, so they can't drift apart. The secret is `app.jwt.secret` (dev: `application-dev.yml`, prod: `JWT_SECRET` from AWS), ≥ 32 bytes |
| `NimbusJwtEncoder.withSecretKey(...).algorithm(HS256)` | Spring Security 7 builder, the same style as the decoder. When login signs a token it must still pass `JwsHeader.with(MacAlgorithm.HS256)` |
| Roles converter: claim `roles`, prefix `ROLE_` | Spring reads a `scope` claim by default. Our token has `"roles": ["ADMIN"]` (`docs/api-contract.md` §3), and `hasRole("ADMIN")` looks for the authority `ROLE_ADMIN` |
| `anyRequest().denyAll()` | Fail safe: a path nobody listed is closed. Before, it was `authenticated()` |
| `OpenApiConfig` with annotations only | `@SecurityScheme(name = "bearerAuth", type = HTTP, scheme = "bearer")` + `@OpenAPIDefinition(security = ...)` applies the scheme to every endpoint, so teammates don't annotate their controllers. Public endpoints just ignore the header |

### How it connects to teammates

| Who | What they get |
|---|---|
| Everyone | Admin endpoints under `/api/v1/admin/**` are ADMIN only, without touching `SecurityConfig`. Swagger "Authorize" to test protected endpoints |
| Stefania | `CurrentUser` reads `sub` (user id) and `roles` from the token that this encoder signs |

### Testing

| Command / test | Result |
|---|---|
| `cd backend && ./gradlew spotlessApply build` | BUILD SUCCESSFUL, **23 tests** (17 before: 8 ours + 9 from Liviu's #22; +6), on local PostgreSQL 18 |
| `studentPassesSecurityOnNormalPaths` | Student token on `/api/v1/assets` → 404 (security let it through; the path doesn't exist yet) |
| `studentIsForbiddenOnAdminPaths` | Student token on `/api/v1/admin/assets` → **403** |
| `adminPassesSecurityOnAdminPaths` | Admin token on `/api/v1/admin/assets` → 404 (let through) |
| `pathsOutsideTheApiAreDeniedEvenWithAToken` | Admin token on `/something-else` → **403** (`denyAll`) |
| `passwordsAreStoredAsBcryptHashes` | Hash starts with `{bcrypt}`, right password matches, wrong one doesn't |
| `swaggerOffersBearerLogin` | `/v3/api-docs` contains `components.securitySchemes.bearerAuth` |

The tests sign **real tokens with the real `JwtEncoder`**, not a mocked user, so they prove encoder → decoder → roles converter → URL rules all fit together. The test tokens have no `exp` claim, so the test doesn't need a clock (the decoder only checks expiry when the claim exists).

### Next

| Step | Where / waits for |
|---|---|
| 401/403 as `ProblemDetail` JSON | Next PR (`05-security-error-json.md`) |
| `UserSeeder`, `AuthService` + register/login endpoints | Calin's `Clock` bean (Liviu's `common.error` exceptions are on `main` since #22) |

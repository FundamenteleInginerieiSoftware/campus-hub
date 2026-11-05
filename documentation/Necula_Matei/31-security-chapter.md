# Security chapter for the final report (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-05
**Branch / PR:** `docs/report-security` (docs only, no code change)
**See also:** `docs/report/security.md` (the chapter), `04-password-jwt-roles.md`, `22-token-account-check.md`, `25-security-pass-tests.md`, `09-aws-architecture.md`

---

## Part 1: In simple terms

1. **The final report needs a chapter on security**, and at the defense the professor asks "why did you do this?". This chapter answers that for everything I built around login and access.
2. **It starts with who could attack the app** (a curious student, a malicious student, an outsider, and our own mistakes) and what each of them could try, with the protection we have against each one.
3. **It explains the login pass (token):** how you get it, what is inside, why it lasts 8 hours, and why the server checks your account again on every click, so a blocked account stops working at once.
4. **A picture (sequence diagram) shows one login and one request**, step by step, including every way a request can be refused.
5. **A table shows who may open what:** visitors, students and admins, for every group of pages and endpoints.
6. **It lists where passwords and keys live**, which ones are fake (for laptops) and which ones are real (only on AWS, never in Git).
7. **The AWS part is marked "planned"**: it is designed, but not built yet.
8. **It ends with the tests that prove all this, the bugs we found and fixed, and an honest list of what is still weak** (for example: no limit on login attempts).

**Result:** one chapter, checked against the real code on `main`, ready to paste into the report and to answer questions at the defense.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `docs/report/security.md` | New: the security chapter (9 sections: threat model, authentication, sequence diagram, RBAC, security errors, secrets, cloud (planned), verification, limitations) |
| `docs/diagrams/jwt-auth-sequence.mmd` | New: Mermaid `sequenceDiagram` of login + an authenticated request, with the 401/403/404 branches. Also embedded in the chapter |
| `documentation/Necula_Matei/31-security-chapter.md` | This note |
| `documentation/Necula_Matei/02-files-explained.md` | Rows for the two new files |
| `documentation/Necula_Matei/README.md` | Row 31 |

### Sources used (every claim was checked against them)

| Topic | Source on `main` (`99d0261`) |
|---|---|
| URL rules, token decoding, account check, bad `sub` | `config/SecurityConfig.java` |
| 401 / 403 / firewall JSON | `config/SecurityErrorHandler.java` |
| Swagger bearer scheme | `config/OpenApiConfig.java` |
| React routes are public | `config/SpaConfig.java`, `SpaFrontendTest` |
| AWS clients, timeouts, no keys | `config/AwsConfig.java`, `storage/service/S3StorageService.java`, `notification/service/SesNotificationService.java` |
| Register, login, token claims | `user/service/AuthService.java`, `user/web/*Request.java`, `LoginResponse`, `UserResponse` |
| `findActiveRole`, `/users/me` | `user/service/UserService.java` |
| First admin, dev accounts | `AdminBootstrap.java`, `UserSeeder.java` |
| Lower-case unique email | `user/domain/User.java`, `V2026_10_21_1528__user_create_users.sql` |
| User id from the token | `common/security/CurrentUser.java` (Stefania) |
| 404 instead of 403 | `reservation/service/ReservationService.loadOwnedOrAdmin` (Calin) |
| Generic 500, no leaks | `common/error/GlobalExceptionHandler.java` (Liviu) |
| Upload checks | `storage/service/StorageValidation.java` (Liviu) |
| Config per profile | `application.yml`, `application-dev.yml`, `application-prod.yml`, `src/test/resources/application-test.yml` |
| Tests | `SecurityConfigTest` (18), `SecurityPassIT` (7), `AuthFlowIT`, `AuthServiceTest`, `AuthRequestsTest`, `AuthControllerTest`, `UserServiceTest`, `AdminBootstrapTest`, `ErrorJsonTest`, `SpaFrontendTest`, `ProdProfileStartupTest`, storage tests |
| Token in `localStorage`, 401 → login | `frontend/src/api/client.ts` (Stefania) |
| Non-root container | `Dockerfile`, `.dockerignore` |
| Planned AWS setup | ADR-002, ADR-003, ADR-005, docs 23 and 29 |
| Secret scanning + push protection | `gh api repos/FundamenteleInginerieiSoftware/campus-hub` (`security_and_analysis`) |
| PR and issue numbers | `git log --oneline origin/main`, docs 13, 20–22, 25, 27–29 |

### Choices

| Choice | Why |
|---|---|
| STRIDE table with a "where in code / test" column | Each threat points at the line or test that handles it, so a question at the defense can be answered by opening that file |
| RBAC table derived from `SecurityConfig` and tests, not from the contract | `docs/api-contract.md` §2 lists 5 rules; the code has 8 since the Dockerfile PR (`/error`, `denyAll` on `/api/**` + `/actuator/**`, public `GET /**`). The chapter follows the code |
| AWS section marked "planned" | No `infra/` and no `deploy.yml` on `main` yet; only the Docker image and the prod adapters exist |
| Limitations only if true in the code | E.g. "no rate limiting": nothing on `main` limits login attempts; "Swagger public in prod": `application-prod.yml` doesn't disable springdoc |
| Diagram checked with the Mermaid parser | `mermaid.parse()` (mermaid 11.4.1 in a scratch folder) accepts both `jwt-auth-sequence.mmd` and the existing `reassignment-sequence.mmd` |

### Things noticed while checking (not changed here, docs-only PR)

| Where | What |
|---|---|
| `docs/api-contract.md` §2 | Still lists 5 rules; the code has 8 (see above). Update in a small docs PR |
| `AuthService.createToken` comment | Says "SecurityConfig turns roles into ROLE_..."; since #117 the role comes from the database |
| `AuthFlowIT` Javadoc | Says the token's "roles" claim is read; same, outdated since #117 |
| `CurrentUser.isAdmin()` Javadoc (Stefania) | Same outdated sentence; tell Stefania |
| Doc 23 env table | Says a `JWT_SECRET` under 32 bytes fails at startup: **checked 6 Nov, correct** (`jwtEncoder` bean throws `KeyLengthException` while the app starts; 31 bytes fails, 32 works) |

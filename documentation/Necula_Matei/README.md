# Necula Matei: personal documentation

**Role:** Member 1, Cloud, Security & Auth (`@matei-necula`)

## Part 1: In simple terms

I make sure only the right people get in, and that the app can run on AWS.

- **Login and accounts:** register, log in, the token that proves who you are, and the rule that admin pages are for admins only.
- **Settings and build:** the files that say which libraries we use and how the app is configured.
- **AWS:** the server, the database, photo storage and e-mail in the cloud, and the recipe (Dockerfile) that packs the whole app into one box.
- **One page in the website:** the admin reservations desk.

Each PR I push comes with one numbered note here (`NN-topic.md`). Every note starts with **Part 1: In simple terms**, then **Part 2: Technical details**.

**Where to start:**
- **Not a programmer (or the professor):** read only Part 1 of `01` (how the project was set up), `09` (the AWS plan), `13` (register and login), `19` (the Docker image) and `02` (what every file does).
- **A teammate who needs the login details:** `04` (passwords, tokens, roles), `05` (401/403 errors), `13` (register/login), `17` (`/users/me`), `07` + `11` (`UserApi`), `20` (old tokens), `22` (blocked accounts), `31` (security chapter).
- **A teammate who needs the AWS details:** `09` (architecture + ADRs), `19` (Dockerfile), `18` (first admin on AWS), `23` + `29` (S3 + SES), `32` (deployment diagrams), `44` + `45` (Terraform).

## Part 2: Technical details

**What I own** (AGENTS.md §1): `backend/build.gradle`, `settings.gradle`, `gradle/` (only I edit them), `application*.yml`, `config/` (`SecurityConfig`), `user/` (auth, JWT, `UserApi`), `messaging/` (SQS, stretch goal), all of AWS (`infra/`, `Dockerfile`, `deploy.yml`, `S3StorageService`, `SesNotificationService`), `.github/CODEOWNERS`, `AGENTS.md`. Frontend: the admin reservations desk page.

`02-files-explained.md` explains every file I added, and is updated in each PR. The PR column says which PR added the note.

| # | File | What it's about | PR |
|---|---|---|---|
| 01 | [01-project-setup-v2.md](01-project-setup-v2.md) | The restart: role split v2, Gradle + Java 25 + PostgreSQL 18, first PRs | #14 |
| 02 | [02-files-explained.md](02-files-explained.md) | What each file does, simple then technical | #14 |
| 03 | [03-user-entity.md](03-user-entity.md) | `users` table, `User` entity and repository | #17 |
| 04 | [04-password-jwt-roles.md](04-password-jwt-roles.md) | Password hashing, JWT signing, admin-only rule, Swagger login | #25 |
| 05 | [05-security-error-json.md](05-security-error-json.md) | 401 and 403 as `ProblemDetail` JSON | #44 |
| 06 | [06-async-scheduling.md](06-async-scheduling.md) | Background work and timers (`@EnableAsync`, `@EnableScheduling`) | #45 |
| 07 | [07-user-api.md](07-user-api.md) | `UserApi` + `UserSummary`: how other modules ask about users | #46 |
| 08 | [08-auth-request-records.md](08-auth-request-records.md) | Register and login request/response records | #47 |
| 09 | [09-aws-architecture.md](09-aws-architecture.md) | AWS account setup, architecture diagram, ADRs 001–005 | #20 |
| 10 | [10-prototype-mockup.md](10-prototype-mockup.md) | Clickable mockup of the app with fake data | #41 |
| 11 | [11-user-service.md](11-user-service.md) | `UserService`: the real `UserApi` | #48 |
| 12 | [12-error-codes-fix.md](12-error-codes-fix.md) | Fix: error `code` was missing on validation and MVC errors | #52 |
| 13 | [13-auth-register-login.md](13-auth-register-login.md) | Register and login with JWT | #54 |
| 14 | [14-user-seeder.md](14-user-seeder.md) | Dev accounts created on startup | #55 |
| 15 | [15-archunit-dependency.md](15-archunit-dependency.md) | ArchUnit library for Calin's architecture tests | #62 |
| 16 | [16-security-probe-paths.md](16-security-probe-paths.md) | Security tests probe paths no controller maps | #70 |
| 17 | [17-users-me.md](17-users-me.md) | "Who am I?": `GET /api/v1/users/me` | #82 |
| 18 | [18-prod-admin-bootstrap.md](18-prod-admin-bootstrap.md) | First admin on AWS from `ADMIN_EMAIL` / `ADMIN_PASSWORD` | #88 |
| 19 | [19-dockerfile.md](19-dockerfile.md) | Dockerfile: Spring Boot serves the React build in one image | #114 |
| 20 | [20-auth-ignores-old-token.md](20-auth-ignores-old-token.md) | Fix: login works even with an old token | #126 |
| 21 | [21-firewall-error-json.md](21-firewall-error-json.md) | Fix: addresses blocked by the firewall get our error format | #127 |
| 22 | [22-token-account-check.md](22-token-account-check.md) | Fix: blocked accounts lose access at once | #128 |
| 23 | [23-s3-ses-adapters.md](23-s3-ses-adapters.md) | Photos in S3, e-mails through SES (prod profile) | #129 |
| 24 | [24-user-created-at-seconds.md](24-user-created-at-seconds.md) | Fix: `users.created_at` stored in whole seconds | #135 |
| 25 | [25-security-pass-tests.md](25-security-pass-tests.md) | Security pass as tests: IDOR, admin-only endpoints, error bodies | #139 |
| 26 | [26-reservations-desk-page.md](26-reservations-desk-page.md) | Reservations desk: the admin's page at the lab counter | #138 |
| 27 | [27-jwt-bad-subject-401.md](27-jwt-bad-subject-401.md) | Fix: a token with a bad user id gets 401, not 500 | #176 |
| 28 | [28-register-trims-email.md](28-register-trims-email.md) | Fix: register accepts spaces around the email | #175 |
| 29 | [29-s3-ses-hardening.md](29-s3-ses-hardening.md) | Fix: missing photos on S3, slow or failing e-mails | #177 |
| 30 | [30-swagger-check.md](30-swagger-check.md) | Swagger check for M1: real security and error format on all 31 endpoints | #184 |
| 31 | [31-security-chapter.md](31-security-chapter.md) | Security chapter of the final report (`docs/report/security.md`) + JWT sequence diagram | #185 |
| 32 | [32-component-deployment-diagrams.md](32-component-deployment-diagrams.md) | UML component diagram + deployment diagrams (laptop/CI and planned AWS) | #186 |
| 33 | [33-mutation-testing.md](33-mutation-testing.md) | Mutation testing (PIT): which rules no test would catch breaking | #180 |
| 34 | [34-demo-flow-test.md](34-demo-flow-test.md) | The whole prototype demo as one test that names the broken step and its owner | #181 |
| 35 | [35-mutation-gaps-tests.md](35-mutation-gaps-tests.md) | Tests for the 5 gaps mutation testing (PIT) found in my code | #182 |
| 36 | [36-desk-sort-ascending.md](36-desk-sort-ascending.md) | Reservations desk: earliest bookings first, on every page | #194 |
| 37 | [37-contract-security-rules.md](37-contract-security-rules.md) | All security rules in the API contract, SSM arrow fixed in the AWS diagram | #195 |
| 38 | [38-project-assistant-design.md](38-project-assistant-design.md) | Projects feature (#140): design and plan, who builds which part | #232 |
| 39 | [39-project-engine.md](39-project-engine.md) | Projects engine (#140): startup loader, bilingual search, live availability endpoints | #236 |
| 40 | [40-project-content.md](40-project-content.md) | Projects content (#140): seven more project guides in Romanian and English | #239 |
| 41 | [41-projects-pages.md](41-projects-pages.md) | Projects pages (#140): search list, project details, slot availability check | #238 |
| 42 | [42-project-reserve-all.md](42-project-reserve-all.md) | Projects "Reserve all" (#140): book every item of a project kit in one go | #243 |
| 43 | [43-project-bundle-banner.md](43-project-bundle-banner.md) | Projects: "Bundle booking confirmed!" banner after Reserve all (passes the bundle id to Calin's #246 banner) | #248 |
| 44 | [44-terraform-network.md](44-terraform-network.md) | Terraform part 1: state bucket, VPC, subnets and firewall (security groups) | #249 |
| 45 | [45-terraform-data.md](45-terraform-data.md) | Terraform part 2: RDS (off until W7), photo bucket, ECR, server role, secrets in SSM | #250 |
| 46 | [46-docker-os-patches.md](46-docker-os-patches.md) | First image in ECR; OS security updates in the Docker image (ECR scan found a critical zlib CVE) | #252 |
| 47 | [47-login-hardening.md](47-login-hardening.md) | Login: passwords over 72 bytes get 401 (BCrypt cut them silently); no transaction held during BCrypt | #271 |
| 48 | [48-project-search-fixes.md](48-project-search-fixes.md) | Projects: search no longer matches words that only share their first 5 letters ("multiplexer" ≠ "multimeter"); `ProjectService` clean-up | #272 |
| 49 | [49-project-details-error-fix.md](49-project-details-error-fix.md) | Project details: error cleared after a good reload, campus clock read when needed | #273 |
| 50 | [50-docs-check.md](50-docs-check.md) | End-of-task docs rule (AGENTS.md §4) + `docs-check` CI job + Gemini/Copilot pointer files | #277, #279 |
| 51 | [51-pickup-pass-backend.md](51-pickup-pass-backend.md) | Pickup pass (backend): a 6-digit code that changes every 30 s, shown by the student and checked by the admin at the desk | #283 |
| 52 | [52-pickup-pass-pages.md](52-pickup-pass-pages.md) | Pickup pass (pages): the student's code page and the Verify dialog on the Reservations desk | #PICKUP2 |

**Team docs:**
- [README.md §7](../../README.md#7-progress-what-each-of-us-has-done): what each of us has done
- [docs/whats-left.md](../../docs/whats-left.md): what is still to do, per member
- [docs/decisions.md](../../docs/decisions.md): team decisions with dates
- [docs/adr/](../../docs/adr/): why we chose this AWS setup (ADRs 001–005)
- [docs/api-contract.md](../../docs/api-contract.md): endpoints, JSON examples, error codes

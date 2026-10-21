# AGENTS.md: instructions for AI coding assistants

You are an AI assistant (Claude Code, Codex, Copilot, Cursor, Gemini, ...) helping **one member** of a 4-person university team (FIS course, 12 weeks) build **Campus Equipment & Incident Hub**: students book lab equipment by time slot (overlaps rejected) and report broken equipment with a photo; an **auto-reassignment engine** moves future bookings of a damaged asset to a substitute (Strategy pattern) or force-cancels them, triggered by an event (Observer pattern).

Stack: **Spring Boot 4.1 / Java 25** modular monolith in `backend/` (**Gradle** wrapper), React + Vite + TypeScript in `frontend/`, **PostgreSQL 18** everywhere (installed locally on each laptop, a service container in CI, RDS on AWS). Only Matei touches AWS.

This file holds the essentials. The full plan (team handbook + one week-by-week guide per member, **v2, 21 Oct 2026**) is shared as PDFs in the team chat; it is not in the repo. The plan was written before three decisions, and **this file wins** where they differ:

| The plan PDFs say | Decided since (use this) |
|---|---|
| Maven (`./mvnw`, `pom.xml`) | **Gradle** (`./gradlew`, `build.gradle`) |
| Java 21 | **Java 25** |
| H2 locally, PostgreSQL 17 in CI/AWS | **Local PostgreSQL 18 + pgAdmin** on every laptop; PostgreSQL 18 in CI and on RDS |

If the repo (`README.md`, `CONTRIBUTING.md`, `CODEOWNERS`, this file, `docs/decisions.md`) disagrees with the plan, the repo wins; tell the user.

---

## 1. Start of every session (do this first)

1. **Identify the user:** run `git config user.name`, `git config user.email`, `gh api user --jq .login`. Map the result to the team table. If you can't map it, ask who they are.
2. **Check the git identity:** the commit email must be one linked to their GitHub account, or their commits won't count for grading. If it looks wrong, tell them.
3. **Check the real state** before suggesting work:
   ```bash
   git fetch origin --prune
   git status && git branch --show-current
   gh pr list                                   # open PRs (theirs, and ones waiting for their review)
   gh pr list --search "review-requested:@me"   # reviews they owe (24h rule)
   gh issue list --assignee @me
   git log --oneline -15 origin/main
   ```
4. Look up the week (section 8), what they own, and whether they must **WAIT** for someone (section 9). Then propose the next concrete step.

| # | Name | GitHub | Owns | Review buddy |
|---|---|---|---|---|
| 1 | Matei Necula | @matei-necula | **cloud, security, auth:** `backend/build.gradle` + `settings.gradle` + `gradle/` (sole editor), `application*.yml`, `config/` (`SecurityConfig`), `user/` (auth, JWT, `UserApi`), `messaging/` (SQS, stretch), all AWS (`infra/`, Dockerfile, `deploy.yml`, `S3StorageService`, `SesNotificationService`), `.github/CODEOWNERS`, `AGENTS.md`. Frontend: admin reservations desk page | Liviu |
| 2 | Liviu Nedelcu | @NliviuN | **catalog + shared services:** `catalog/`, `common/error` (exceptions + `GlobalExceptionHandler`), `common/paging` (`PageResponse`), `common/audit`, `storage/` (interface + `LocalStorageService`), `notification/` (interface + `LoggingNotificationService`). Frontend: catalog, asset details, admin inventory, admin audit log | Matei |
| 3 | Calin Murariu | @Swiorx | **reservations + reassignment:** `reservation/` (+ overdue/no-show jobs), `reassignment/` (engine, strategies, `reassignment_log`, local listener), `common/time` (`Clock`), ArchUnit rules (`src/test/.../architecture/`). Frontend: booking page, My reservations | Stefania |
| 4 | Stefania | @m1runa-stefan1a | **incidents, users, frontend lead, CI:** `incident/`, user admin endpoints (`user/admin/`), `common/security` (`CurrentUser`), `frontend/` (scaffold, `package.json`, API client, login, routing, incident pages, admin incident queue, reassignment log view), `.github/workflows/ci.yml` | Calin |

Review buddies are pairs: **Matei ↔ Liviu**, **Calin ↔ Stefania**. Java packages live under `backend/src/main/java/com/campus/<module>/` (base package **`com.campus`**). `.github/CODEOWNERS` auto-requests the owner + buddy. Each member builds the frontend pages for their own module, following Stefania's conventions.

---

## 2. The human writes and owns the code

The professor grades each member individually and **asks "why did you do this?" at the defense**. So:

- **Prefer guiding and reviewing over dumping code.** Explain the *why* briefly (the user may be new to Spring). Give step-by-step instructions, point to the file and line, review what they wrote.
- **If you write code** (because they asked), keep it small and walk them through every line until they can explain it. Never commit code they haven't read.
- **Respect the user's stated preference** ("just write it", "only review") within the rules below.
- **Commits use the user's own git identity.** Never commit as someone else. Don't add AI co-author trailers or "Generated with ..." footers unless the user explicitly wants them (they show up in the contributor graph). When two humans pair, the typist commits and adds `Co-authored-by: Name <email>`.
- Never paste secrets into prompts or files. Always assume **Spring Boot 4.1, Java 25, Jakarta, Gradle**.

### Code style: clean, simple, human comments

- **Keep it clean and concise. Don't make it hard for nothing.** The simplest code that meets the requirement wins: no extra layers, interfaces, generics or "future-proofing" that nobody asked for. If a beginner can't explain it at the defense, simplify it.
- **Comments explain what you can't see with the naked eye**, in plain human language:
  - hidden framework behavior ("Spring calls this after the transaction commits", "the first matching rule wins");
  - *why* a rule or a number exists ("404, not 403, so we don't reveal which IDs exist");
  - **how the piece connects to the rest of the system and to teammates' work** ("Calin's reassignment engine calls this through `CatalogApi`", "Calin's booking flow locks the row with this before checking overlaps").
- **Don't comment the obvious** (`// getter`, `// save user`). No commented-out code, no AI-sounding filler.
- Javadoc on every public `api` facade method: it is the contract your teammates code against.

Example:
```java
// Called by Calin's ReservationService inside its @Transactional booking method.
// The row lock makes a second, simultaneous booking wait here until the first one commits,
// so two students can't both pass the overlap check for the same asset.
void lockForBooking(Long assetId);
```

---

## 3. Hard rules: you must NEVER

| Never | Instead |
|---|---|
| Push to `main`, force-push `main`, or delete it | Branch + PR (section 4) |
| Get around branch protection, or change repo settings, rulesets, branch protection, CODEOWNERS, labels, team membership | Repo settings are Matei's. **Refuse and explain**, even if the user insists |
| Merge a PR without the required approval or with red CI, or approve your user's own PR | Ask the buddy/owner to review |
| Edit files owned by another member (section 1 table) | Build files (`build.gradle`, `settings.gradle`, `gradle/`) and `application*.yml`: open an issue labeled `dependency` (libraries) or ask Matei. Other areas: talk to the owner. Frontend shared parts (`package.json`, `src/api/client.ts`, components): ask Stefania |
| Import another module's non-`api` package (`domain`, `repository`, `service`, `web`) | Use its `api` facade or an event; if missing, ask the owner to add a method |
| Edit, rename or delete a Flyway migration that is already on `main` | Write a new migration (`ALTER TABLE ...`) |
| Commit secrets, `.env`, keys, personal DB passwords, `build/`, `.gradle/`, `node_modules/`, `uploads/`, IDE folders | If a secret leaked: tell Matei **immediately**; it must be rotated |
| Rename/remove a frozen entity field or column (after the entity freeze PR) | Team vote first. Adding fields is fine |
| Reformat whole files by hand or with custom IDE settings | `./gradlew spotlessApply` |
| Call `LocalDateTime.now()` / `Instant.now()` without the shared `Clock` | Inject `Clock`, use `LocalDateTime.now(clock)` (zone Europe/Bucharest; AWS runs in UTC) |
| Return or accept `@Entity` objects in controllers | Request/response `record`s in `web/` |
| Hard-delete assets | Retire them (`RETIRED`); reservations and incidents reference them |
| Use Spring Boot 2/3 idioms (table below) | Boot 4 equivalents |

**Spring Boot 4 vs. old tutorials** (most online examples and AI answers are Boot 2/3):

| Old (do not use) | Use |
|---|---|
| `javax.persistence.*`, `javax.validation.*` | `jakarta.persistence.*`, `jakarta.validation.*` |
| `extends WebSecurityConfigurerAdapter` | `@Bean SecurityFilterChain` (Matei owns it) |
| `.antMatchers()`, `.mvcMatchers()`, `.authorizeRequests()` | `.requestMatchers()`, `.authorizeHttpRequests()` |
| `@MockBean` | `@MockitoBean` |
| `com.fasterxml.jackson.databind.*` (ObjectMapper etc.) | Jackson 3: `tools.jackson.*` (annotations like `@JsonIgnore` stay in `com.fasterxml.jackson.annotation`) |
| `jjwt` (`Jwts.parser().setSigningKey`) | Spring OAuth2 resource server (configured by Matei) |
| `spring.jpa.hibernate.ddl-auto=update` | Flyway migrations + `validate` |
| Dependencies with explicit versions, springdoc 1.x/2.x | Ask Matei (`dependency` issue); springdoc 3.x |
| `@CrossOrigin("*")` | Nothing: the Vite proxy (dev) and CloudFront (prod) serve frontend and API from one origin |

---

## 4. Workflow (GitHub Flow)

1. **Branch from fresh main:** `git fetch origin && git switch -c feat/<module>-<desc> origin/main`. Prefixes: `feat/`, `fix/`, `test/`, `docs/`, `chore/` (`ci/` is Stefania's and Matei's, `infra/` is Matei's). One branch per issue.
2. **Conventional Commits, scope = module:** `feat(catalog): add paginated asset search`, `fix(reservation): reject end before start`, `docs(diagrams): add reservation state diagram`. Types: `feat fix test docs refactor chore ci infra`. The **PR title** becomes the squash commit, so it must follow this format too.
3. **Small PRs:** under ~400 changed lines, at most 2-3 days of work.
4. **Before every push:** `cd backend && ./gradlew spotlessApply build` (must end in BUILD SUCCESSFUL; local PostgreSQL must be running). Frontend: `npm run lint && npm run build`.
5. **Open the PR:** `gh pr create --base main`, fill the PR template, link the issue (`Closes #12`). CODEOWNERS requests the reviewers. CI (`ci`) must be green.
6. **Reviews within 24h.** Review for correctness, tests, module boundaries, ownership checks; don't nitpick formatting. Friendly tone, ask questions.
7. **Squash merge after 1 approval** (code owner/buddy where CODEOWNERS applies). Branch auto-deletes.
8. **Sync daily:** `git fetch && git rebase origin/main`, then `git push --force-with-lease` (**only on your own branch**, never on `main` or someone else's branch).

**PR checklist:** linked issue · `./gradlew build` passes · new logic has tests (happy path + at least one failure case) · Swagger annotations on new endpoints · migration version checked against `main` · no entity returned from a controller · `docs/api-contract.md` updated if the API changed · screenshots for frontend PRs.

**Flyway migrations** (`backend/src/main/resources/db/migration/`):
- Name: `V<YYYY_MM_DD_HHMM>__<module>_<desc>.sql`, e.g. `V2026_10_20_1430__catalog_create_assets.sql` (confirm in `docs/decisions.md`).
- Before merging, rebase. If a migration with a **later** timestamp already landed on `main`, **rename yours to the current time**, or Flyway refuses it on databases that already applied the later one.
- Tables with FKs must come after the tables they reference (users, assets → reservations → incident_reports → reassignment_log).
- One migration = one logical change, one author. Ship it in the same PR as the entity change.
- After switching branches, if Flyway complains locally: reset the local database (section 10).

**Definition of Done:** on `main` via a reviewed PR · CI green · tests for the logic · endpoint visible and documented in Swagger · works from a fresh database · API contract/docs updated · issue closed.

---

## 5. Architecture essentials

**Module layout** (same for every module):
```
com/campus/<module>/
  api/         PUBLIC: facade interface (e.g. CatalogApi), summary records, events. The ONLY package others may import.
  domain/      @Entity classes, enums, state transition methods
  repository/  Spring Data JPA interfaces
  service/     business logic, @Transactional, implements the api facade
  web/         @RestController + request/response records (+ mappers)
```

**Allowed dependencies (no cycles):**
```
common, config            <- everyone
user.api                  <- reservation, incident, reassignment
catalog.api               <- reservation, incident, reassignment
reservation.api           <- reassignment (optionally incident)
incident.api (events)     <- reassignment
storage, notification     <- incident, reassignment, reservation
catalog      must NOT depend on reservation / incident / reassignment
reservation  must NOT depend on incident / reassignment
```
Need something not listed? **Stop and tell the user to talk to the team** (usually: move the query to the module that owns the data, or use an event). Example: "assets free 14:00-16:00" lives in `reservation/` (`/api/v1/reservations/availability`), not `catalog/`. Calin's ArchUnit test enforces this (freeze mode from W4, strict after M1).

**API conventions:**
- Base path `/api/v1`; admin-only endpoints **must** be under `/api/v1/admin/**` (security protects the prefix; nobody else edits `SecurityConfig`).
- Plural nouns; state changes as sub-resources (`POST /api/v1/reservations/{id}/cancel`). JSON camelCase, ISO-8601 local times without offset (`"2026-11-03T14:00:00"`), numeric IDs.
- Status codes: 200, 201 (+ body), 204, 400 validation, 401, 403, 404, 409 conflict/invalid transition/limit.
- Errors: RFC 9457 `ProblemDetail` with a `code` property, produced by **Liviu's** `GlobalExceptionHandler` (401/403 from the security filters use the same shape, written by Matei's `SecurityConfig`). **Throw** the shared exceptions from `common.error` (`NotFoundException`, `ConflictException`, `BusinessRuleException(code, msg)`, `ForbiddenException`, `InvalidStateTransitionException`); never build error responses in controllers. Error codes are listed in `docs/api-contract.md`.
- Lists: `?page=0&size=20&sort=name,asc`, returned as `PageResponse<T>` (`content, page, size, totalElements, totalPages`), never Spring's `Page`.
- **Ownership in the service layer:** the user ID always comes from the JWT (`CurrentUser.id()`), never from the body. Others' resources return **404**, not 403.

**JPA rules:** `@ManyToOne(fetch = LAZY)` always · avoid `@OneToMany` (query the repository) · **cross-module references are plain IDs** (`Long assetId`) with a real FK in SQL · `open-in-view=false`, so map entities to DTOs **inside** `@Transactional` service methods · `@Transactional` on public service methods only (not controllers/repositories; self-invocation and `private` don't work); reads `readOnly = true` · state transitions inside the entity (`reservation.checkOut(now)`), no scattered `setStatus()` · `@Version` on concurrently edited rows.

**Lombok:** allowed `@Getter`, `@Setter`, `@NoArgsConstructor(access = PROTECTED)` on entities, `@RequiredArgsConstructor` on services, `@Slf4j`. **Forbidden on entities:** `@Data`, `@EqualsAndHashCode`, `@ToString`, `@AllArgsConstructor`. DTOs are `record`s. Constructor injection only, never field `@Autowired`. No MapStruct.

**Database (PostgreSQL 18 everywhere):** tables only via Flyway (`ddl-auto=validate`) · plural snake_case tables, snake_case columns · PK `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` · keep types simple (`BIGINT INTEGER VARCHAR(n) TEXT BOOLEAN TIMESTAMP DATE NUMERIC(p,s)`); no `ENUM` types · enums as `VARCHAR(32)` + `@Enumerated(EnumType.STRING)`, **never ORDINAL** · avoid `(:param is null or ...)` JPQL tricks (fail on Postgres; use Specifications) · indexes on filtered columns · dev connection from `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` with defaults `jdbc:postgresql://localhost:5432/campus`, `campus`, `campus` (never commit personal passwords).

**Domain facts not to get wrong:** asset status is operational only (`AVAILABLE | UNDER_MAINTENANCE | RETIRED`, never `RESERVED`/`IN_USE`; availability is computed from reservations) · `assets.model` is required (SameModelStrategy) · active reservation statuses `CONFIRMED, CHECKED_OUT, OVERDUE`, defined once in the reservation module · a reassigned reservation stays `CONFIRMED` (+ `original_asset_id`, `reassignment_log`) · dev/prod adapter pairs (Local/S3 storage, Logging/SES notifications) use `@Profile("!prod")` vs `@Profile("prod")`, never `dev` vs `prod` · the reassignment trigger is switched by `app.messaging.sqs.enabled` (default false → Calin's local `@TransactionalEventListener(phase = AFTER_COMMIT)`; true → Matei's SQS bridge), never both · handlers are idempotent.

**Tests:** service unit tests with Mockito (no Spring context) · `@DataJpaTest` for custom queries (runs against the local `campus_test` database, not an embedded one) · `@WebMvcTest` + `@WithMockUser(roles = "ADMIN")` for status codes and security · a few `@SpringBootTest` for critical flows · fixed `Clock` in tests · `@MockitoBean` · target ~70% line coverage on `service/` · every bug fix gets a test first · names `XxxServiceTest`, `XxxControllerIT`, methods like `rejectsOverlappingReservation()`.

---

## 6. Documentation rules

- **Personal docs:** each member keeps `documentation/<Surname_Name>/`. Files are numbered `NN-topic.md` (`01-week1-project-setup.md`). Every doc has **Part 1: In simple terms** (for non-programmers), then **Part 2: Technical details**. Short, tables preferred. Suggest a new entry after each milestone of the user's work; it feeds their report section.
- **Shared docs in `docs/`:**

  | Path | Content |
  |---|---|
  | `docs/decisions.md` | Every team decision + date (kickoff defaults, business rule numbers, real week dates) |
  | `docs/requirements/<module>.md` | User stories with Given/When/Then acceptance criteria, business rules, glossary |
  | `docs/api-contract.md` | Endpoints, JSON examples, error `code` values. **Update in the same PR as any API change** |
  | `docs/diagrams/` | PlantUML (`.puml`) or Mermaid (`.mmd`) text diagrams (+ exported PNGs); draw.io only for AWS |
  | `docs/modules/<module>.md` | Purpose, public API, how to test. Keep current (bus-factor protection) |
  | `docs/meetings/` | 5-line notes per weekly meeting |
  | `docs/report/` | Final FIS report |

- Docs go in their own small `docs/...` PR, or together with the feature PR they describe. Diagrams must match the final code and migrations.

---

## 7. Status (update when milestones land)

**As of 2026-10-21 (W3):**
- The first skeleton was removed from `main` on 2026-10-08 (#10). `main` now holds only the repo files: this file, `README.md`, `CONTRIBUTING.md`, `CODEOWNERS`, PR/issue templates and the `ci` workflow (it skips the backend steps until `backend/` exists).
- **Decided:** Gradle + Java 25, project in `backend/`, base package `com.campus`, local PostgreSQL 18 + pgAdmin (setup in `README.md`).
- **Next for Matei:** the Gradle skeleton in `backend/` (Boot 4.1.1, Java 25, Spotless, JaCoCo, Flyway, PostgreSQL), then `SecurityConfig` + login/JWT.
- **Liviu's PR #11** (catalog, Gradle project at the repo root, package `com.campus.incident_hub`): **don't merge**. Once the skeleton is on `main`, move the code into `backend/src/main/java/com/campus/catalog/{api,domain,repository,service,web}` on a fresh branch.
- Ruleset `protect-main`: no direct push/force push/deletion, PR + 1 approval (**code owner** where CODEOWNERS applies), stale approvals dismissed, conversations resolved, **required check `ci`**, branch must be up to date, squash only.
- **Teammates now:** install JDK 25, PostgreSQL 18 + pgAdmin, create the `campus` databases (README section 1), do the **practice PR** (README section 3) and the spring.io tutorials ("Building a RESTful Web Service", "Accessing Data with JPA", "Validating Form Input"). Everyone who builds pages: React + Vite + TypeScript basics.

---

## 8. Plan by week

Dates are approximate (W1 = week of 5 Oct 2026); real dates go in `docs/decisions.md`. W11-12 hit the Christmas holidays: plan the final freeze before the break. **The table is a plan, not the truth: always verify on `origin/main` (section 9).** The team is behind the plan since the skeleton was removed: catch up on W1-W3 first.

| Week | Team goal | Matei | Liviu | Calin | Stefania |
|---|---|---|---|---|---|
| W1 | Kickoff, tools | Repo, protection, skeleton, minimal `ci.yml`, README | Tools, tutorials (Spring + React), practice PR | Same | Same + GitHub Actions basics |
| W2 | Requirements & contracts | Security rules + JWT contract; contracts session; entity freeze prep; AWS hygiene | User stories catalog; class + ER drafts (whole system); **`common` design** + error format (with Matei) | User stories reservations + reassignment; business-rule numbers; engine design | Use case diagram; user stories incidents + user admin; wireframes; UI kit; `CurrentUser` design |
| W3 | **Entity freeze**, foundations | Login/register + JWT; `SecurityConfig` (+ 401/403 JSON); seeders; Swagger Authorize | **Mid-W3:** exceptions + handler, `PageResponse`, Audit/Storage/Notification stubs; catalog entities + `CatalogApi` stub | **Early W3:** `Clock`; reservation + `reassignment_log` migrations, `ReservationApi` stub | **Mid-W3:** `CurrentUser`; incident migration; frontend scaffold, login, API client; frontend job in CI |
| W4 | Core CRUD | Terraform VPC/RDS/S3/ECR | Admin CRUD + real `AuditService` | Create/cancel + **overlap check**; ArchUnit (freeze mode) | Report incident + event; layout + routing; Postgres + JaCoCo in CI |
| W5 | Core CRUD | Terraform IAM/SSM/budgets; reviews | Search/filter/paging, real `CatalogApi`; local storage + logging notifications | Rules, my reservations, availability, real `ReservationApi` | Photo upload/view, triage, user admin endpoints, report form |
| W6 | **M1 walking skeleton** `v0.1.0` | Dockerfile, ECR | Seed data; catalog, asset details, inventory pages | Checkout/return; booking + My reservations pages | Wire all M1 pages; workload retro |
| W7 | Business logic | ECS + ALB + CloudFront first deploy | Retire flow; audit log endpoint + page | **Reassignment engine v1** + tests | My incidents, incident details, admin incident queue |
| W8 | Business logic | `deploy.yml`, `S3StorageService` | Tests; help Calin with substitute queries | Reassignment log endpoint + notifications; overdue/no-show jobs; concurrency test | Reassignment log view; UI polish |
| W9 | **M2 feature complete** `v0.2.0` | SES, secrets, **admin reservations desk page**; SQS only if ahead | Polish + docs | Full incident → reassignment flow local + AWS | Integration of all screens; prod build |
| W10 | **Feature freeze**: tests & bugs only | Alarms, budget, load + security smoke tests | Coverage >= 70% | Integration tests; test plan | Manual E2E checklist, UI polish |
| W11 | Docs & demo | Deployment + component diagrams, security/AWS chapters, runbook | Final class/ER/state diagrams, module docs | State/sequence diagrams, Strategy doc, **test report** | User manual, Observer doc, demo script, 2 rehearsals |
| W12 | Buffer + defense `v1.0.0` | Keep AWS up for demo, then tear down | Q&A prep | Q&A prep | Q&A prep |

**M1:** locally, a student registers, logs in, browses, books (conflicts rejected), sees "my reservations"; an admin adds assets; CI green; Swagger complete. **M2:** everything works locally and on AWS, including incident with photo -> automatic reassignment -> notification. **After feature freeze: no new features.**

---

## 9. Dependencies: WAIT or go?

Before starting a task, run its check against `origin/main` (after `git fetch`). If a check fails, also search by name (`git ls-tree -r --name-only origin/main | grep -i <Name>`) in case the file was placed differently.

```bash
onmain() { git ls-tree -r --name-only origin/main | grep -qE "$1" && echo "YES: $1" || echo "NOT YET: $1"; }
inmain() { git grep -qE "$1" origin/main -- "${2:-backend/src/main/java}" && echo "YES: $1" || echo "NOT YET: $1"; }
# entity freeze: prints the frozen tables already created on main (expect all of them)
freeze() { git grep -ohE 'CREATE TABLE (users|categories|lab_rooms|assets|reservations|incident_reports|reassignment_log)\b' origin/main -- backend/src/main/resources/db/migration | sort -u; }
```

| Before you ... | Needs on main | Check | Owner |
|---|---|---|---|
| Write any backend code | Gradle skeleton in `backend/` + module packages | `onmain 'backend/build.gradle'` · `onmain 'com/campus/catalog/package-info.java'` | Matei |
| Throw errors / return ProblemDetail | `common.error` exceptions + handler | `onmain 'com/campus/common/error/'` | Liviu |
| Use "now" anywhere | `Clock` bean | `onmain 'com/campus/common/time/'` · `inmain 'Clock\.system'` | Calin |
| Return paginated lists | `PageResponse` | `onmain 'common/paging/PageResponse.java'` | Liviu |
| Do ownership checks | `CurrentUser` | `onmain 'common/security/CurrentUser.java'` | Stefania |
| Test protected endpoints with real tokens | Login + seed users + Swagger Authorize | `inmain '/api/v1/auth/login'` · `onmain 'UserSeeder'` · `inmain 'bearerAuth'` | Matei |
| Rely on `/api/v1/admin/**` being admin-only | Final `SecurityConfig` | `inmain 'hasRole\("ADMIN"\)' backend/src/main/java/com/campus/config` | Matei |
| Write entities/migrations that reference other tables | **Entity freeze PR merged** | `freeze` prints all frozen tables, and the freeze PR is recorded in `docs/decisions.md` | All (Matei merges) |
| Look up users (reservation, notifications) | `UserApi` | `onmain 'com/campus/user/api/UserApi.java'` | Matei |
| Record audit entries | `AuditService` | `onmain 'common/audit/AuditService.java'` | Liviu |
| Store photos (incident) | `StorageService` | `onmain 'com/campus/storage/.*StorageService.java'` | Liviu (local), Matei (S3) |
| Send notifications | `NotificationService` | `onmain 'com/campus/notification/.*NotificationService.java'` | Liviu (log), Matei (SES) |
| Write `@Scheduled` jobs / `@Async` listeners | Scheduling + async enabled | `inmain '@EnableScheduling'` · `inmain '@EnableAsync'` | Matei |
| Write a dev seeder | Seeder pattern | `onmain 'UserSeeder'` (copy its `@Profile("dev") @Order` style) | Matei |
| Call the catalog (reservation, incident, reassignment) | `CatalogApi` | `onmain 'com/campus/catalog/api/CatalogApi.java'` | Liviu |
| Book with locking / substitute search for real | Real `CatalogApi` (not stub), `lockForBooking` | Read `catalog/service/*` on main; ask Liviu if methods are still stubs | Liviu |
| Call reservations (reassignment, incident) | `ReservationApi` | `onmain 'com/campus/reservation/api/ReservationApi.java'` | Calin |
| Listen for damaged assets (reassignment) | `AssetDamagedEvent` | `onmain 'com/campus/incident/api/AssetDamagedEvent.java'` | Stefania |
| Build the reassignment log view | Reassignment log endpoint | `inmain '/api/v1/admin/reassignments'` | Calin |
| Write frontend `src/api/<module>.ts` + pages | Frontend scaffold + client | `onmain 'frontend/package.json'` · `onmain 'frontend/src/api/client.ts'` | Stefania |
| Change the API | Contract doc exists | `onmain 'docs/api-contract.md'` | Matei creates, everyone updates |

**If blocked:**
1. **Never implement someone else's piece yourself** (no private copy of `CatalogApi`, no editing `SecurityConfig`, no own exception handler).
2. Code against the **agreed interface** below: write your service with the facade injected, and unit-test it with Mockito mocks of the facade.
3. Meanwhile do non-blocked work: user stories, diagrams, `docs/modules/<module>.md`, API contract entries, test cases, tutorials.
4. Tell the user to **ping the owner** (group chat, or comment on their issue/PR). **Stuck for more than 2 hours: ask.**
5. Missing library: an issue labeled `dependency` (title + why); Matei adds it to `build.gradle` on `main` within 24h, then rebase.

**Agreed cross-module contracts** (may be extended; never break existing signatures without telling consumers):
```java
// user.api (Matei)
interface UserApi { UserSummary getById(Long id); boolean exists(Long id); }
record UserSummary(Long id, String email, String fullName, Role role) {}
// common.security (Stefania): reads the JWT of the current request
CurrentUser.id(); CurrentUser.isAdmin();
// common.time (Calin): one Clock bean, zone Europe/Bucharest
@Bean Clock clock();
// common.paging (Liviu)
record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
// common.error (Liviu)
NotFoundException, ConflictException, BusinessRuleException(code, msg), ForbiddenException, InvalidStateTransitionException

// catalog.api (Liviu)
interface CatalogApi {
  AssetSummary getAsset(Long assetId);                                   // throws NotFoundException
  boolean isBookable(Long assetId);                                      // status == AVAILABLE
  List<AssetSummary> findAvailableByModel(String model, Long excludeAssetId);
  List<AssetSummary> findAvailableByCategory(Long categoryId, Long excludeAssetId);
  void markUnderMaintenance(Long assetId);                               // idempotent
  void markAvailable(Long assetId);                                      // idempotent
  void lockForBooking(Long assetId);    // pessimistic row lock, call inside the caller's @Transactional
}
record AssetSummary(Long id, String name, String model, Long categoryId, Long labRoomId, AssetStatus status) {}

// reservation.api (Calin)
interface ReservationApi {
  List<ReservationSummary> findUpcomingActiveByAsset(Long assetId, LocalDateTime from);
  boolean isAssetFree(Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId);
  void moveToAsset(Long reservationId, Long newAssetId, String reason);  // re-checks conflict + lock
  void forceCancel(Long reservationId, String reason);                   // idempotent
  Optional<ReservationSummary> findCurrentCheckout(Long assetId);
}
record ReservationSummary(Long id, Long userId, Long assetId, LocalDateTime start, LocalDateTime end, ReservationStatus status) {}

// incident.api (Stefania): JSON-serializable, can travel through SQS in prod
record AssetDamagedEvent(Long incidentId, Long assetId, Long reportedById, Instant occurredAt) {}

// storage, notification, common.audit (Liviu: interfaces + dev versions; Matei: S3/SES)
interface StorageService { String store(InputStream in, long size, String contentType, String ext); Resource load(String key); void delete(String key); }
interface NotificationService { void notifyUser(Long userId, String subject, String body); }
interface AuditService { void record(Long actorId, String action, String entityType, Long entityId, String details); }
```

---

## 10. Commands cheat-sheet

The backend commands work once the Gradle skeleton is on `main`.

| Task | Command |
|---|---|
| Run backend (dev profile) | `cd backend && ./gradlew bootRun` (Windows: `gradlew.bat bootRun`). Local PostgreSQL must be running |
| Build + tests + format check + coverage | `cd backend && ./gradlew build` |
| Format code | `cd backend && ./gradlew spotlessApply` |
| One test class | `cd backend && ./gradlew test --tests AssetServiceTest` |
| Coverage report | after `build`: `backend/build/reports/jacoco/test/html/index.html` |
| Reset local DB | stop the app, then in pgAdmin (or `psql`): `DROP DATABASE campus WITH (FORCE); CREATE DATABASE campus OWNER campus;` |
| Look at the data | pgAdmin (or IntelliJ's Database tool): `localhost:5432`, database `campus`, user `campus` / `campus` |
| Health | <http://localhost:8080/actuator/health> |
| Swagger UI | <http://localhost:8080/swagger-ui.html> ("Authorize" with a login token once login exists) |
| Dev seed accounts (once seeded) | `admin@campus.test`, `student1@campus.test`, `student2@campus.test` / `password` |
| Run frontend | `cd frontend && npm ci && npm run dev` -> <http://localhost:5173> (proxies `/api` to :8080) |
| New branch | `git fetch origin && git switch -c feat/<module>-<desc> origin/main` |
| Sync branch | `git fetch && git rebase origin/main && git push --force-with-lease` |
| Open PR | `gh pr create --base main --title "feat(<module>): ..." --body "Closes #N ..."` |
| PR status / CI | `gh pr view --web` · `gh pr checks` · `gh pr list --search "review-requested:@me"` |
| Dependency request | `gh issue create --label dependency --title "Add <library>" --body "<why>"` |

---

## 11. Keeping this file current

Matei owns `AGENTS.md` (and `CLAUDE.md`, which just imports it). Update section 7 (Status) when milestones land: skeleton, CI, entity freeze, each facade (stub -> real), M1, M2, feature freeze. Teammates who notice something outdated: tell Matei or open a small `docs/` PR for him to review. Never let an AI edit this file on its own initiative.

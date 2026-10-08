# AGENTS.md: instructions for AI coding assistants

You are an AI assistant (Claude Code, Codex, Copilot, Cursor, Gemini, ...) helping **one member** of a 4-person university team (FIS course, 12 weeks) build **Campus Equipment & Incident Hub**: students book lab equipment by time slot (overlaps rejected) and report broken equipment with a photo; an **auto-reassignment engine** moves future bookings of a damaged asset to a substitute (Strategy pattern) or force-cancels them, triggered by an event (Observer pattern).

Stack: **Spring Boot 4.1 / Java 21** modular monolith in `backend/` (Maven wrapper), React + Vite + TypeScript in `frontend/` (from W3), H2 locally, PostgreSQL 17 in CI/AWS. Only Matei touches AWS.

This file is self-contained for the essentials. The full **team handbook** (`00-TEAM-HANDBOOK.md`) and the **member guides** (`01-`...`04-MEMBER*.md`) were shared by Matei outside the repo. If the user has them locally, ask for the path and read the handbook plus their own guide before planning work. If this file and the handbook disagree, the handbook and `docs/decisions.md` win; tell the user.

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
| 1 | Matei Necula | @matei-necula | platform, security, cloud: `backend/pom.xml` (sole editor), `application*.yml`, `config/`, `common/`, `user/`, `storage/`, `notification/`, `messaging/`, `.github/`, `infra/`, `docker-compose.yml`, CI/CD, `AGENTS.md` | Liviu |
| 2 | Liviu Nedelcu | @NliviuN | catalog & inventory: `catalog/` | Matei |
| 3 | Calin Murariu | @Swiorx | reservations & scheduling: `reservation/` | Stefania |
| 4 | Stefania | @m1runa-stefan1a | incidents, reassignment, frontend: `incident/`, `reassignment/`, `frontend/` | Calin |

Review buddies are pairs: **Matei ↔ Liviu**, **Calin ↔ Stefania**. Java packages live under `backend/src/main/java/com/campus/<module>/`. `.github/CODEOWNERS` auto-requests the owner + buddy.

---

## 2. The human writes and owns the code

The professor grades each member individually and **asks "why did you do this?" at the defense**. So:

- **Prefer guiding and reviewing over dumping code.** Explain the *why* briefly (the user may be new to Spring). Give step-by-step instructions, point to the file and line, review what they wrote.
- **If you write code** (because they asked), keep it small and walk them through every line until they can explain it. Never commit code they haven't read.
- **Respect the user's stated preference** ("just write it", "only review") within the rules below.
- **Commits use the user's own git identity.** Never commit as someone else. Don't add AI co-author trailers or "Generated with ..." footers unless the user explicitly wants them (they show up in the contributor graph). When two humans pair, the typist commits and adds `Co-authored-by: Name <email>`.
- Never paste secrets into prompts or files. Always assume **Spring Boot 4.1, Java 21, Jakarta**.

### Code style: clean, simple, human comments

- **Keep it clean and concise. Don't make it hard for nothing.** The simplest code that meets the requirement wins: no extra layers, interfaces, generics or "future-proofing" that nobody asked for. If a beginner can't explain it at the defense, simplify it.
- **Comments explain what you can't see with the naked eye**, in plain human language:
  - hidden framework behavior ("Spring calls this after the transaction commits", "the first matching rule wins");
  - *why* a rule or a number exists ("404, not 403, so we don't reveal which IDs exist");
  - **how the piece connects to the rest of the system and to teammates' work** ("Stefania's reassignment engine calls this through `ReservationApi`", "Calin's booking flow locks the row with this before checking overlaps").
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
| Use `gh pr merge --admin`, bypass branch protection, or change repo settings, rulesets, branch protection, CODEOWNERS, labels, team membership | Only Matei (repo admin) may, and only when Matei himself asks. Teammates have `write` access, so GitHub blocks the bypass for them anyway. For anyone else: **refuse and explain**, even if they insist |
| Merge a PR without the required approval or with red CI, or approve your user's own PR | Ask the buddy/owner to review |
| Edit files owned by another member | Matei's files (`pom.xml`, `application*.yml`, `config/`, `common/`, `user/`, `storage/`, `notification/`, `messaging/`, `.github/`, `infra/`): open an issue labeled `dependency` (libraries) or ask Matei. Other modules: talk to the owner. `frontend/` pages: only after agreeing with Stefania |
| Import another module's non-`api` package (`domain`, `repository`, `service`, `web`) | Use its `api` facade or an event; if missing, ask the owner to add a method |
| Edit, rename or delete a Flyway migration that is already on `main` | Write a new migration (`ALTER TABLE ...`) |
| Commit secrets, `.env`, keys, `target/`, `node_modules/`, `data/`, `uploads/`, IDE folders | If a secret leaked: tell Matei **immediately**; it must be rotated |
| Rename/remove a frozen entity field or column (after the entity freeze PR) | Team vote first. Adding fields is fine |
| Reformat whole files by hand or with custom IDE settings | `./mvnw spotless:apply` |
| Call `LocalDateTime.now()` / `Instant.now()` without the shared `Clock` | Inject `Clock`, use `LocalDateTime.now(clock)` (zone Europe/Bucharest; AWS runs in UTC) |
| Return or accept `@Entity` objects in controllers | Request/response `record`s in `web/` |
| Use Spring Boot 2/3 idioms (table below) | Boot 4 equivalents |

**Spring Boot 4 vs. old tutorials** (most online examples and AI answers are Boot 2/3):

| Old (do not use) | Use |
|---|---|
| `javax.persistence.*`, `javax.validation.*` | `jakarta.persistence.*`, `jakarta.validation.*` |
| `extends WebSecurityConfigurerAdapter` | `@Bean SecurityFilterChain` (Matei owns it) |
| `.antMatchers()`, `.mvcMatchers()`, `.authorizeRequests()` | `.requestMatchers()`, `.authorizeHttpRequests()` |
| `@MockBean` | `@MockitoBean` |
| `com.fasterxml.jackson.databind.*` (ObjectMapper etc.) | Jackson 3: `tools.jackson.*` (annotations like `@JsonIgnore` stay in `com.fasterxml.jackson.annotation`) |
| `jjwt` (`Jwts.parser().setSigningKey`) | Spring OAuth2 resource server (already configured by Matei) |
| `spring.jpa.hibernate.ddl-auto=update` | Flyway migrations + `validate` |
| `<dependency>` with explicit versions, springdoc 1.x/2.x | Ask Matei (`dependency` issue); springdoc 3.x |

---

## 4. Workflow (GitHub Flow)

1. **Branch from fresh main:** `git fetch origin && git switch -c feat/<module>-<desc> origin/main`. Prefixes: `feat/`, `fix/`, `test/`, `docs/`, `chore/` (`ci/`, `infra/` are Matei's). One branch per issue.
2. **Conventional Commits, scope = module:** `feat(catalog): add paginated asset search`, `fix(reservation): reject end before start`, `docs(diagrams): add reservation state diagram`. Types: `feat fix test docs refactor chore ci infra`. The **PR title** becomes the squash commit, so it must follow this format too.
3. **Small PRs:** under ~400 changed lines, at most 2-3 days of work.
4. **Before every push:** `cd backend && ./mvnw spotless:apply && ./mvnw verify` (must end in BUILD SUCCESS). Frontend: `npm run build` (+ lint).
5. **Open the PR:** `gh pr create --base main`, fill the PR template (once it exists), link the issue (`Closes #12`), request the review buddy. CI (`ci`) must be green.
6. **Reviews within 24h.** Review for correctness, tests, module boundaries, ownership checks; don't nitpick formatting. Friendly tone, ask questions.
7. **Squash merge after 1 approval** (code owner/buddy where CODEOWNERS applies). Branch auto-deletes.
8. **Sync daily:** `git fetch && git rebase origin/main`, then `git push --force-with-lease` (**only on your own branch**, never on `main` or someone else's branch).

**PR checklist:** linked issue · `./mvnw verify` passes · new logic has tests (happy path + at least one failure case) · Swagger annotations on new endpoints · migration version checked against `main` · no entity returned from a controller · `docs/api-contract.md` updated if the API changed · screenshots for frontend PRs.

**Flyway migrations** (`backend/src/main/resources/db/migration/`):
- Name: `V<YYYY_MM_DD_HHMM>__<module>_<desc>.sql`, e.g. `V2026_10_20_1430__catalog_create_assets.sql` (default; confirm in `docs/decisions.md`).
- Before merging, rebase. If a migration with a **later** timestamp already landed on `main`, **rename yours to the current time**, or Flyway refuses it on databases that already applied the later one.
- Tables with FKs must come after the tables they reference (users, assets → reservations → incident_reports).
- One migration = one logical change, one author. Ship it in the same PR as the entity change.
- After switching branches, if Flyway complains locally: stop the app, delete `backend/data/`, restart.

**Definition of Done:** on `main` via a reviewed PR · CI green · tests for the logic · endpoint visible and documented in Swagger · works from a fresh DB (delete `backend/data/`) · API contract/docs updated · issue closed.

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
Need something not listed? **Stop and tell the user to talk to the team** (usually: move the query to the module that owns the data, or use an event). Example: "assets free 14:00-16:00" lives in `reservation/` (`/api/v1/reservations/availability`), not `catalog/`. An ArchUnit test will enforce this from about W5.

**API conventions:**
- Base path `/api/v1`; admin-only endpoints **must** be under `/api/v1/admin/**` (security protects the prefix; nobody else edits `SecurityConfig`).
- Plural nouns; state changes as sub-resources (`POST /api/v1/reservations/{id}/cancel`). JSON camelCase, ISO-8601 local times without offset (`"2026-11-03T14:00:00"`), numeric IDs.
- Status codes: 200, 201 (+ body), 204, 400 validation, 401, 403, 404, 409 conflict/invalid transition/limit.
- Errors: RFC 9457 `ProblemDetail` with a `code` property, produced by Matei's global handler. **Throw** the shared exceptions from `common.error` (`NotFoundException`, `ConflictException`, `BusinessRuleException(code, msg)`, `ForbiddenException`); never build error responses in controllers. Error codes are listed in `docs/api-contract.md`.
- Lists: `?page=0&size=20&sort=name,asc`, returned as `PageResponse<T>` (`content, page, size, totalElements, totalPages`), never Spring's `Page`.
- **Ownership in the service layer:** the user ID always comes from the JWT (`CurrentUser.id()`), never from the body. Others' resources return **404**, not 403.

**JPA rules:** `@ManyToOne(fetch = LAZY)` always · avoid `@OneToMany` (query the repository) · **cross-module references are plain IDs** (`Long assetId`) with a real FK in SQL · `open-in-view=false`, so map entities to DTOs **inside** `@Transactional` service methods · `@Transactional` on public service methods only (not controllers/repositories; self-invocation and `private` don't work); reads `readOnly = true` · state transitions inside the entity (`reservation.checkOut(now)`), no scattered `setStatus()` · `@Version` on concurrently edited rows.

**Lombok:** allowed `@Getter`, `@Setter`, `@NoArgsConstructor(access = PROTECTED)` on entities, `@RequiredArgsConstructor` on services, `@Slf4j`. **Forbidden on entities:** `@Data`, `@EqualsAndHashCode`, `@ToString`, `@AllArgsConstructor`. DTOs are `record`s. Constructor injection only, never field `@Autowired`. No MapStruct.

**DB portability (H2 locally, PostgreSQL in CI/prod):** tables only via Flyway (`ddl-auto=validate`) · plural snake_case tables, snake_case columns · PK `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` · types only `BIGINT INTEGER VARCHAR(n) TEXT BOOLEAN TIMESTAMP DATE NUMERIC(p,s)` · no `SERIAL`, `JSONB`, arrays, partial indexes, Postgres-only functions, `ENUM` types · enums as `VARCHAR(32)` + `@Enumerated(EnumType.STRING)`, **never ORDINAL** · avoid `(:param is null or ...)` JPQL tricks (fail on Postgres) · indexes on filtered columns.

**Domain facts not to get wrong:** asset status is operational only (`AVAILABLE | UNDER_MAINTENANCE | RETIRED`, never `RESERVED`/`IN_USE`; availability is computed from reservations) · `assets.model` is required (SameModelStrategy) · active reservation statuses `CONFIRMED, CHECKED_OUT, OVERDUE`, defined once in the reservation module · a reassigned reservation stays `CONFIRMED` (+ `original_asset_id`, `reassignment_log`) · dev/prod bean pairs use `@Profile("prod")` vs `@Profile("!prod")`, never `dev` vs `prod` · event listeners are `@TransactionalEventListener(phase = AFTER_COMMIT)`, handlers idempotent.

**Tests:** service unit tests with Mockito (no Spring context) · `@DataJpaTest` for custom queries · `@WebMvcTest` + `@WithMockUser(roles = "ADMIN")` for status codes and security · a few `@SpringBootTest` for critical flows · fixed `Clock` in tests · `@MockitoBean` · target ~70% line coverage on `service/` · every bug fix gets a test first · names `XxxServiceTest`, `XxxControllerIT`, methods like `rejectsOverlappingReservation()`.

---

## 6. Documentation rules

- **Personal docs:** each member keeps `documentation/<Surname_Name>/` (e.g. `documentation/Necula_Matei/`). Files are numbered `NN-topic.md` (`01-week1-project-setup.md`). Every doc has **Part 1: In simple terms** (for non-programmers), then **Part 2: Technical details**. Short, tables preferred. Copy the style of `documentation/Necula_Matei/`. Suggest a new entry after each milestone of the user's work; it feeds their report section.
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

**As of 2026-10-08 (W1):**
- On `main`: initial skeleton (Boot 4.1.1, springdoc, Spotless, JaCoCo, dev/prod profiles), README, Matei's docs, `CODEOWNERS` (code owner review required).
- Ruleset `protect-main`: no direct push/force push/deletion, PR + 1 approval, stale approvals dismissed, conversations resolved, squash only. CI check `ci` becomes required once it lands.
- In review (Matei's PRs): #7 security skeleton (`GET /api/v1/auth/ping`, temporary `SecurityConfig`, first Flyway migration, Swagger + H2 console open), #4 CI workflow `ci` (tests on PostgreSQL 17), #5 PR/issue templates + `CONTRIBUTING.md` + module `package-info.java` files + labels (`dependency`, `task`, `user-story`, module labels). Check `gh pr list --state merged` to see what already landed.
- **Teammates now:** install tools, clone, run (`./mvnw verify`, `./mvnw spring-boot:run`), do the **practice PR** (README section 3: `documentation/<Surname_Name>/README.md`), do the spring.io tutorials ("Building a RESTful Web Service", "Accessing Data with JPA", "Validating Form Input"). Stefania also: React + Vite + TypeScript basics.

---

## 8. Plan by week

Dates are approximate (assuming W1 = week of 5 Oct 2026); real dates go in `docs/decisions.md`. W11-12 hit the Christmas holidays: plan the final freeze before the break. **The table is a plan, not the truth: always verify on `origin/main` (section 9).**

| Week | Team goal | Matei | Liviu (catalog) | Calin (reservation) | Stefania (incident, reassignment, frontend) |
|---|---|---|---|---|---|
| W1 | Kickoff, tools | Repo, protection, skeleton, CI, README, templates, ping endpoint | Tools, tutorials, practice PR | Same + `LocalDateTime`/`Clock`/`@Transactional` reading | Same + React/Vite/TS tutorials |
| W2 | Requirements & contracts | Security rules + error format in `api-contract.md`; `common` design; leads contracts session; prepares entity freeze | User stories `catalog.md`; **class + ER diagram drafts (whole system)**; `CatalogApi` signatures | User stories; **business-rule numbers**; `ReservationApi` with Stefania; state + sequence drafts | **Use case diagram**; user stories; wireframes; pick UI kit (Mantine); date + login contracts |
| W3 | **Entity freeze**, foundations | Login/register + JWT; `SecurityConfig` final; `Clock`, errors, `PageResponse`, `CurrentUser`; Storage/Notification/Audit stubs; `UserSeeder`; Swagger Authorize; merges freeze PR | Catalog migration + entities + `CatalogApi` stub | Reservation migration + entity + transitions + `ReservationApi` stub | Incident + `reassignment_log` migrations + entity; frontend scaffold, proxy, API client, login pages |
| W4 | Core CRUD | ArchUnit (warning); Terraform VPC/RDS/S3 | Admin CRUD + validation + audit + tests | Create/cancel + **overlap check** + test matrix | Report incident (no photo) + asset to maintenance + event; catalog page |
| W5 | Core CRUD | `LocalStorageService`, notification + audit impl, Postgres in CI | Search/filter/paging (Specifications), real `CatalogApi`, `lockForBooking` | Limits/rules, my reservations, availability, real `ReservationApi` | Photo upload/view, triage endpoints, booking UI, report form |
| W6 | **M1 walking skeleton** `v0.1.0` | Dockerfile, ECR | Seed data, module doc, catalog page with Stefania | Checkout/return, admin list, seed data | M1 pages integrated; workload retro |
| W7 | Business logic | ECS + ALB + CloudFront first deploy | Retire-asset flow decision, edge cases | Overdue/no-show `@Scheduled` jobs (idempotent) | **Reassignment engine v1** (listener, strategies) + tests |
| W8 | Business logic | `deploy.yml`, `S3StorageService` | Optimistic locking tests; help with substitute queries | Concurrency test; harden `moveToAsset`/`forceCancel` | Reassignment log, admin incident queue, "moved" UI |
| W9 | **M2 feature complete** `v0.2.0` | SQS bridge + consumer + DLQ, SES, secrets | Polish + docs | Polish + docs, AWS time check | Full flow local + AWS, UI polish, prod build |
| W10 | **Feature freeze**: tests & bugs only | Alarms, budget, load + security smoke tests | Coverage >= 70% | Test plan + integration tests of full flows | Manual E2E checklist, UI polish |
| W11 | Docs & demo | Deployment + component diagrams, AWS/security chapters, runbook | Final class/ER/state diagrams, report section | State/sequence diagrams, **test report** | User manual, pattern docs, demo script, 2 rehearsals |
| W12 | Buffer + defense `v1.0.0` | Keep AWS up for demo, then tear down | Q&A prep | Q&A prep | Q&A prep |

**M1:** locally, a student registers, logs in, browses, books (conflicts rejected), sees "my reservations"; an admin adds assets; CI green; Swagger complete. **M2:** everything works locally and on AWS, including incident with photo -> automatic reassignment -> notification. **After feature freeze: no new features.**

---

## 9. Dependencies: WAIT or go?

Before starting a task, run its check against `origin/main` (after `git fetch`). Paths follow the handbook layout; if a check fails, also search by name (`git ls-tree -r --name-only origin/main | grep -i <Name>`) in case the file was placed differently.

```bash
onmain() { git ls-tree -r --name-only origin/main | grep -qE "$1" && echo "YES: $1" || echo "NOT YET: $1"; }
inmain() { git grep -qE "$1" origin/main -- "${2:-backend/src/main/java}" && echo "YES: $1" || echo "NOT YET: $1"; }
# entity freeze: prints the frozen tables already created on main (expect all of them)
freeze() { git grep -ohE 'CREATE TABLE (users|categories|lab_rooms|assets|reservations|incident_reports|reassignment_log)\b' origin/main -- backend/src/main/resources/db/migration | sort -u; }
```

| Before you ... | Needs on main | Check | Owner |
|---|---|---|---|
| Write any feature code | Security skeleton, CI, `package-info.java` per module | `onmain 'com/campus/config/SecurityConfig.java'` · `onmain '.github/workflows/ci.yml'` · `onmain 'com/campus/catalog/package-info.java'` | Matei |
| Throw errors / return ProblemDetail | `common.error` exceptions + handler | `onmain 'com/campus/common/error/'` | Matei |
| Use "now" anywhere | `Clock` bean | `onmain 'com/campus/common/time/'` · `inmain 'Clock\.system'` | Matei |
| Return paginated lists | `PageResponse` | `onmain 'common/paging/PageResponse.java'` | Matei |
| Do ownership checks | `CurrentUser` | `onmain 'common/security/CurrentUser.java'` | Matei |
| Test protected endpoints with real tokens | Login + seed users + Swagger Authorize | `inmain '/api/v1/auth/login'` · `onmain 'UserSeeder'` · `inmain 'bearerAuth'` | Matei |
| Rely on `/api/v1/admin/**` being admin-only | Final `SecurityConfig` | `inmain 'hasRole\("ADMIN"\)' backend/src/main/java/com/campus/config` | Matei |
| Write entities/migrations that reference other tables | **Entity freeze PR merged** | `freeze` prints all frozen tables, and the freeze PR is recorded in `docs/decisions.md` | All (Matei merges) |
| Look up users (reservation, notifications) | `UserApi` | `onmain 'com/campus/user/api/UserApi.java'` | Matei |
| Record audit entries | `AuditService` | `onmain 'common/audit/AuditService.java'` | Matei |
| Store photos (incident) | `StorageService` | `onmain 'com/campus/storage/.*StorageService.java'` | Matei |
| Send notifications | `NotificationService` | `onmain 'com/campus/notification/.*NotificationService.java'` | Matei |
| Write `@Scheduled` jobs / `@Async` listeners | Scheduling + async enabled | `inmain '@EnableScheduling'` · `inmain '@EnableAsync'` | Matei |
| Write a dev seeder | Seeder pattern | `onmain 'UserSeeder'` (copy its `@Profile("dev") @Order` style) | Matei |
| Call the catalog (reservation, incident, reassignment) | `CatalogApi` | `onmain 'com/campus/catalog/api/CatalogApi.java'` | Liviu |
| Book with locking / substitute search for real | Real `CatalogApi` (not stub), `lockForBooking` | Read `catalog/service/*` on main; ask Liviu if methods are still stubs | Liviu |
| Call reservations (reassignment, incident) | `ReservationApi` | `onmain 'com/campus/reservation/api/ReservationApi.java'` | Calin |
| Listen for damaged assets (reassignment) | `AssetDamagedEvent` | `onmain 'com/campus/incident/api/AssetDamagedEvent.java'` | Stefania |
| Write frontend `src/api/<module>.ts` + types | Frontend scaffold + client; agreement with Stefania | `onmain 'frontend/package.json'` · `onmain 'frontend/src/api/client.ts'` | Stefania |
| Change the API | Contract doc exists | `onmain 'docs/api-contract.md'` | Matei creates, everyone updates |

**If blocked:**
1. **Never implement someone else's piece yourself** (no private copy of `CatalogApi`, no editing `SecurityConfig`).
2. Code against the **agreed interface** below: write your service with the facade injected, and unit-test it with Mockito mocks of the facade.
3. Meanwhile do non-blocked work: user stories, diagrams, `docs/modules/<module>.md`, API contract entries, test cases, tutorials.
4. Tell the user to **ping the owner** (group chat, or comment on their issue/PR). **Stuck for more than 2 hours: ask.**
5. Missing library: an issue labeled `dependency` (title + why); Matei adds it on `main` within 24h, then rebase.

**Agreed cross-module contracts** (may be extended; never break existing signatures without telling consumers):
```java
// user.api (Matei)
interface UserApi { UserSummary getById(Long id); boolean exists(Long id); }
record UserSummary(Long id, String email, String fullName, Role role) {}
// common.security (Matei): reads the JWT of the current request
CurrentUser.id(); CurrentUser.isAdmin();

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

// incident.api (Stefania): JSON-serializable, also travels through SQS in prod
record AssetDamagedEvent(Long incidentId, Long assetId, Long reportedById, Instant occurredAt) {}

// storage, notification, common.audit (Matei)
interface StorageService { String store(InputStream in, long size, String contentType, String ext); Resource load(String key); void delete(String key); }
interface NotificationService { void notifyUser(Long userId, String subject, String body); }
interface AuditService { void record(Long actorId, String action, String entityType, Long entityId, String details); }
```

---

## 10. Commands cheat-sheet

| Task | Command |
|---|---|
| Run backend (dev profile, H2) | `cd backend && ./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`) |
| Build + tests + format check | `cd backend && ./mvnw verify` |
| Format code | `cd backend && ./mvnw spotless:apply` |
| One test class | `cd backend && ./mvnw test -Dtest=AssetServiceTest` |
| Coverage report | after `verify`: `backend/target/site/jacoco/index.html` |
| Reset local DB | stop the app, `rm -rf backend/data/`, start again |
| Health | <http://localhost:8080/actuator/health> |
| Ping (public) | <http://localhost:8080/api/v1/auth/ping> -> `{"message":"pong"}` |
| Swagger UI | <http://localhost:8080/swagger-ui.html> ("Authorize" with a login token from W3) |
| H2 console | <http://localhost:8080/h2-console>, JDBC URL `jdbc:h2:file:./data/campusdb`, user `sa`, empty password |
| Dev seed accounts (from W3) | `admin@campus.test`, `student1@campus.test`, `student2@campus.test` / `password` |
| Run frontend (from W3) | `cd frontend && npm ci && npm run dev` -> <http://localhost:5173> (proxies `/api` to :8080) |
| New branch | `git fetch origin && git switch -c feat/<module>-<desc> origin/main` |
| Sync branch | `git fetch && git rebase origin/main && git push --force-with-lease` |
| Open PR | `gh pr create --base main --title "feat(<module>): ..." --body "Closes #N ..."` |
| PR status / CI | `gh pr view --web` · `gh pr checks` · `gh pr list --search "review-requested:@me"` |
| Dependency request | `gh issue create --label dependency --title "Add <library>" --body "<why>"` |

---

## 11. Keeping this file current

Matei owns `AGENTS.md` (and `CLAUDE.md`, which just imports it). Update section 7 (Status) when milestones land: security skeleton, CI required, entity freeze, each facade (stub -> real), M1, M2, feature freeze. Teammates who notice something outdated: tell Matei or open a small `docs/` PR for him to review. Never let an AI edit this file on its own initiative.

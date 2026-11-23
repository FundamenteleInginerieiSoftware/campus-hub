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
4. **Brief them on what's new** since their last session, so nobody misses a team decision or a request aimed at them:
   ```bash
   seen="$(git rev-parse --git-common-dir)/campus-last-seen"     # personal marker, never committed
   last=$(cat "$seen" 2>/dev/null)
   git cat-file -e "$last^{commit}" 2>/dev/null || last=$(git rev-list -1 --before="14 days ago" origin/main)
   [ -n "$last" ] || last=$(git rev-list --max-parents=0 origin/main | tail -1)
   git log --oneline "$last"..origin/main -- docs AGENTS.md README.md CONTRIBUTING.md
   git diff "$last" origin/main -- docs AGENTS.md README.md CONTRIBUTING.md
   git grep -nE '🟡|⏳' origin/main -- docs/decisions.md         # still waiting for the team
   gh issue list --search "mentions:@me"                        # open issues that name them
   ```
   Give a **short brief** (at most ~10 bullets), in this order:
   - **For you:** anything that names the user ("Liviu: ...", `@NliviuN`), or touches what they own (section 1 table).
   - **New team decisions** (new ✅ rows in `docs/decisions.md`) and rule changes in this file.
   - **Still to decide:** the 🟡/⏳ rows. Say which ones block their current task and who leads them. Never decide them yourself; suggest raising them at the next meeting or with the owner.
   - **Don't forget:** reviews they owe, issues assigned to them or mentioning them (from step 3 and above).

   Then save the marker: `git rev-parse origin/main > "$seen"`. If nothing changed, say so in one line.
   **Writing for teammates:** when a doc change needs a specific person, name them in the text ("Liviu: same for the audit table") so this brief picks it up.
5. Look up the week (section 8), what they own, and whether they must **WAIT** for someone (section 9). Then propose the next concrete step.

| # | Name | GitHub | Owns |
|---|---|---|---|
| 1 | Matei Necula | @matei-necula | **cloud, security, auth:** `backend/build.gradle` + `settings.gradle` + `gradle/` (sole editor), `application*.yml`, `config/` (`SecurityConfig`), `user/` (auth, JWT, `UserApi`), `messaging/` (SQS, stretch), all AWS (`infra/`, Dockerfile, `deploy.yml`, `S3StorageService`, `SesNotificationService`), `.github/CODEOWNERS`, `AGENTS.md`. Frontend: admin reservations desk page |
| 2 | Liviu Nedelcu | @NliviuN | **catalog + shared services:** `catalog/`, `common/error` (exceptions + `GlobalExceptionHandler`), `common/paging` (`PageResponse`), `common/audit`, `storage/` (interface + `LocalStorageService`), `notification/` (interface + `LoggingNotificationService`). Frontend: catalog, asset details, admin inventory, admin audit log |
| 3 | Calin Murariu | @Swiorx | **reservations + reassignment:** `reservation/` (+ overdue/no-show jobs), `reassignment/` (engine, strategies, `reassignment_log`, local listener), `common/time` (`Clock`), ArchUnit rules (`src/test/.../architecture/`). Frontend: booking page, My reservations |
| 4 | Stefania | @m1runa-stefan1a | **incidents, users, frontend lead, CI:** `incident/`, user admin endpoints (`user/admin/`), `common/security` (`CurrentUser`), `frontend/` (scaffold, `package.json`, API client, login, routing, incident pages, admin incident queue, reassignment log view), `.github/workflows/ci.yml` |

**Reviews:** anyone can review anyone's PR (no review buddies since 4 Nov 2026). `.github/CODEOWNERS` requests all teammates on every PR; 1 approval from any of them is enough. Java packages live under `backend/src/main/java/com/campus/<module>/` (base package **`com.campus`**). Each member builds the frontend pages for their own module, following Stefania's conventions.

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
  - **bug fixes:** when you fix a bug or something that didn't work, leave a short comment at the fix saying what was wrong and why the fix works ("Bug fixed: reading the field on the Spring proxy always gave null. Fix: read it through a method, which the proxy passes on to the real object."). The author can then explain it at the defense.
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
| Merge a PR without the required approval or with red CI, or approve your user's own PR | Ask any teammate to review |
| **Touch a teammate's code** (section 1 table): edit their files, create files in their module or package, write their tables' migrations or their events/facades, or push, merge or open a PR for anything that does. This includes "just a quick fix" or "to unblock myself", even if the user insists | Code against the agreed interface and wait until the owner has pushed their part (section 9). Build files (`build.gradle`, `settings.gradle`, `gradle/`) and `application*.yml`: open an issue labeled `dependency` (libraries) or ask Matei. Other areas: talk to the owner. Frontend shared parts (`package.json`, `src/api/client.ts`, components): ask Stefania |
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
5. **Open the PR:** `gh pr create --base main`, fill the PR template, link the issue (`Closes #12`). CODEOWNERS requests all teammates. CI (`ci`) must be green.
6. **Reviews within 24h**, by any teammate (the first one free picks it up and says so on the PR). Review for correctness, tests, module boundaries, ownership checks; don't nitpick formatting. Friendly tone, ask questions.
7. **Squash merge after 1 approval** from any teammate. Branch auto-deletes.
8. **Sync daily:** `git fetch && git rebase origin/main`, then `git push --force-with-lease` (**only on your own branch**, never on `main` or someone else's branch).

**PR checklist:** linked issue · `./gradlew build` passes · new logic has tests (happy path + at least one failure case) · Swagger annotations on new endpoints · migration version checked against `main` · no entity returned from a controller · `docs/api-contract.md` updated if the API changed · personal doc + your lines in `docs/whats-left.md` and section 7 updated · screenshots for frontend PRs.

**Flyway migrations** (`backend/src/main/resources/db/migration/`):
- Name: `V<YYYY_MM_DD_HHMM>__<module>_<desc>.sql`, e.g. `V2026_10_20_1430__catalog_create_assets.sql` (confirm in `docs/decisions.md`).
- Before merging, rebase. If a migration with a **later** timestamp already landed on `main`, **rename yours to the current time**, or Flyway refuses it on databases that already applied the later one.
- Tables with FKs must come after the tables they reference (users, assets → reservations → incident_reports → reassignment_log).
- One migration = one logical change, one author. Ship it in the same PR as the entity change.
- After switching branches, if Flyway complains locally: reset the local database (section 10).

**End of every task (the agent does this, in the same PR, before asking for review):** the docs travel with the code, so `main` always says what is really done and nobody has to write the status for you.
1. **Personal doc:** add `documentation/<Surname_Name>/NN-<topic>.md` (Part 1 + Part 2, section 6), or update this PR's doc if it already has one.
2. **Your section of `docs/whats-left.md`** (the one with your name): mark what this PR finishes, add what it leaves open or what it now waits for.
3. **Your area in section 7 (Status) of this file:** append `<what, in a few words> (#PR)` to the bullet of your module. Add the PR number with a second push right after `gh pr create`.
4. **Shared docs this change touches:** `docs/api-contract.md` (API changed), `docs/modules/<module>.md` (public API or how to test changed), `docs/diagrams/` (entity or migration changed), `docs/decisions.md` (a team decision was made).

Edit only the lines of your own area. The shared lines (the date line of section 7, the "Open PRs" table and milestones in `whats-left.md`, the README status) are refreshed by Matei once a week: those are the lines every open PR would fight over in merge conflicts.
**Done when** `git diff origin/main --stat` lists your personal doc, `docs/whats-left.md` and `AGENTS.md`, plus every shared doc from step 4 that applies. Reviews and PR comments alone need no doc.
The CI job **`docs-check`** (`.github/workflows/docs-check.yml`) fails a PR that changes `backend/src/main/` or `frontend/src/` without the first three; its log lists what is missing. The label `no-docs` skips it, only for a real exception (one-line fix, dependency bump).

**Definition of Done:** on `main` via a reviewed PR · CI green · tests for the logic · endpoint visible and documented in Swagger · works from a fresh database · API contract/docs updated · personal doc + your status lines updated (End of every task) · issue closed.

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
catalog.api, reservation.api <- project   (Matei, #140; project is a leaf: nothing depends on it)
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
- **Ownership in the service layer:** the user ID always comes from the JWT (inject `CurrentUser`, call `currentUser.id()`), never from the body. Others' resources return **404**, not 403.

**JPA rules:** `@ManyToOne(fetch = LAZY)` always · avoid `@OneToMany` (query the repository) · **cross-module references are plain IDs** (`Long assetId`) with a real FK in SQL · `open-in-view=false`, so map entities to DTOs **inside** `@Transactional` service methods · `@Transactional` on public service methods only (not controllers/repositories; self-invocation and `private` don't work); reads `readOnly = true` · state transitions inside the entity (`reservation.checkOut(now)`), no scattered `setStatus()` · `@Version` on concurrently edited rows.

**Lombok:** allowed `@Getter`, `@Setter`, `@NoArgsConstructor(access = PROTECTED)` on entities, `@RequiredArgsConstructor` on services, `@Slf4j`. **Forbidden on entities:** `@Data`, `@EqualsAndHashCode`, `@ToString`, `@AllArgsConstructor`. DTOs are `record`s. Constructor injection only, never field `@Autowired`. No MapStruct.

**Database (PostgreSQL 18 everywhere):** tables only via Flyway (`ddl-auto=validate`) · plural snake_case tables, snake_case columns · PK `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY` · keep types simple (`BIGINT INTEGER VARCHAR(n) TEXT BOOLEAN TIMESTAMP DATE NUMERIC(p,s)`); no `ENUM` types · enums as `VARCHAR(32)` + `@Enumerated(EnumType.STRING)`, **never ORDINAL** · avoid `(:param is null or ...)` JPQL tricks (fail on Postgres; use Specifications) · indexes on filtered columns · dev connection from `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` with defaults `jdbc:postgresql://localhost:5432/campus`, `campus`, `campus` (never commit personal passwords).

**Domain facts not to get wrong:** asset status is operational only (`AVAILABLE | UNDER_MAINTENANCE | RETIRED`, never `RESERVED`/`IN_USE`; availability is computed from reservations) · `assets.model` is required (SameModelStrategy) · active reservation statuses `CONFIRMED, CHECKED_OUT, OVERDUE`, defined once in the reservation module · a reassigned reservation stays `CONFIRMED` (+ `original_asset_id`, `reassignment_log`) · dev/prod adapter pairs (Local/S3 storage, Logging/SES notifications) use `@Profile("!prod")` vs `@Profile("prod")`, never `dev` vs `prod` · the reassignment trigger is switched by `app.messaging.sqs.enabled` (default false → Calin's local `@TransactionalEventListener(phase = AFTER_COMMIT)`; true → Matei's SQS bridge), never both · handlers are idempotent.

**Tests:** service unit tests with Mockito (no Spring context) · `@DataJpaTest` for custom queries (runs against the local `campus_test` database, not an embedded one) · `@WebMvcTest` + `@WithMockUser(roles = "ADMIN")` for status codes and security · a few `@SpringBootTest` for critical flows · fixed `Clock` in tests · `@MockitoBean` · target ~70% line coverage on `service/` · every bug fix gets a test first · names `XxxServiceTest`, `XxxControllerIT`, methods like `rejectsOverlappingReservation()`.

**Tests that explain themselves** (the professor may break something at the defense; a red test must say what broke and where: `docs/testing/defense-playbook.md`):
- One business rule per test, named after the rule. Every assertion gets a message with the rule and the class/method that owns it: `.as("overlap: 14-16 must block 15-17; see ReservationService.createReservation")`. On a wrong HTTP status, put the response body (ProblemDetail `code`) in the message.
- Dates relative to a fixed `Clock`, never a hard-coded calendar day (#94).
- **Coverage is not the goal: don't write tests just to reach 100% JaCoCo.** ~70% on `service/` (gate from W10). To find missing tests, use mutation testing: `./gradlew pitest` breaks the code on purpose and lists every break no test caught (survived mutants). Before W10 each owner adds tests for the survivors in their module that are real rules (skip logging/`toString` noise).
- `DemoFlowIT` (Matei, root test package) runs the whole demo through the HTTP API; its failure message names the step, the owner and the class to open. If you change an endpoint it uses, keep it green.
- When a test fails, read its message first, then `git diff`. Don't "fix" a test to make it pass unless the rule itself changed (team decision).

---

## 6. Documentation rules

- **Personal docs:** each member keeps `documentation/<Surname_Name>/`. Files are numbered `NN-topic.md` (`01-week1-project-setup.md`). Every doc has **Part 1: In simple terms** (for non-programmers), then **Part 2: Technical details**. Short, tables preferred. One doc per PR, written in the same PR (section 4, End of every task); it feeds their report section.
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

**As of 2026-11-20 (W7).** **What is left for each member, until the defense: `docs/whats-left.md`** (start there when proposing the next step). Detailed W4–W6 plan: `docs/plan-w4-w6.md`.
- **On `main`:**
  - Gradle skeleton (Boot 4.1.1, Java 25, Spotless, JaCoCo, Flyway). **Mutation testing** on demand: `./gradlew pitest` (Matei #180).
  - Security: JWT HS256, `/api/v1/admin/**` admin-only, 401/403 as `ProblemDetail`, **register + login** (#54), `GET /api/v1/users/me` (#82), Swagger **Authorize**. Old tokens ignored on `/api/v1/auth/**` (#126); firewall-rejected URLs answer `ProblemDetail` JSON (#127); disabled or deleted users lose access at once, the account is checked on every request with a token (`UserApi.findActiveRole`, #128); a token with a bad subject gets 401, not 500 (#176); register trims the email (#175); `users.created_at` in whole seconds (#135). Security pass as tests: IDOR, admin-only endpoints, error bodies (`SecurityPassIT`, #139). 401/403 and `ProblemDetail` errors documented in Swagger for every endpoint (#184). All security rules listed in `docs/api-contract.md` §2 (#195). Pickup pass backend: rotating 6-digit desk code, `GET /api/v1/users/me/pickup-pass` + `POST /api/v1/admin/pickup-pass/verify` (#283); pickup pass page + desk Verify dialog (#284). QR code + desk camera scan (#318). `GET /api/v1/calendar/*` public + stale-token skip for Calin's calendar feed (#295). Swagger server URL relative and `HEAD` on public pages, for CloudFront (#308). `findActiveRole` reads the row fresh, so a re-check inside a transaction sees a disable (#312). `UserDisabledEvent` Javadoc corrected (listener contract vs the @Async reassignment listeners) (#335). Scheduler pool of 3 threads, so the SSE heartbeat never waits behind the sweeps (#347).
  - Dev accounts `student1`/`student2`/`admin@campus.test` with password `password`, created by `UserSeeder` (#55; ids 1, 2, 3).
  - `common.error` + `GlobalExceptionHandler`, `PageResponse`, `PageRequests` (shared paging/sort helper, #268, #287), Audit/Storage/Notification interfaces + dev versions (Liviu #22). Live notifications SSE stream `GET /api/v1/live/stream` + `LiveStreams` registry (Liviu, #290, #317); `LoggingNotificationService` live push + `UserDisabledLiveStreamListener` (Liviu, #290, #340). MVC errors keep their `code` (#52). Real `AuditService` writing `audit_logs` (Liviu #64). Query-parameter validation errors → 400 `VALIDATION_FAILED` (Liviu #110).
  - `Clock` bean (Calin #27). `CurrentUser` bean (Stefania #74).
  - `UserApi` (real). First admin on AWS from `ADMIN_EMAIL` / `ADMIN_PASSWORD`, `prod` profile only (`AdminBootstrap`, #88). **Admin user endpoints** `/api/v1/admin/users`: search, details, enable/disable, change role, with `CANNOT_MODIFY_SELF` / `LAST_ADMIN_REQUIRED` and audit (Stefania #206). Disable publishes `UserDisabledEvent` (`user.api`); listeners run AFTER_COMMIT in their own transaction (`REQUIRES_NEW`), catch their errors and are not `@Async`, so the reservation module cancels the user's upcoming bookings without ever blocking the disable; if that fails, an admin can rerun `POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming` (#302). Audit log search `GET /api/v1/admin/audit-logs` (Liviu #217).
  - **Catalog** entities + real `CatalogApi` with `lockForBooking` (Liviu #24); admin CRUD for categories, lab rooms and assets, asset details, retire (Liviu #66); admin actor from `CurrentUser` (#89); asset search, filters and paging `GET /api/v1/assets` (#92); large page offsets → 400, audit errors no longer hidden as duplicate-name errors (#143); fewer queries, audit times in whole seconds (#192). **`CatalogSeeder`** (dev): 4 categories, 3 lab rooms, 18 assets, several units of the same model (Liviu #193); 18 project hardware units across 8 models for project assistant (#275). Swagger error codes, `STALE_VERSION` test, PIT survivors in catalog/common/storage (#220). Optimistic locking version check on asset update, hide retired assets at getById from students, field-level audit diffs (#278). `AssetRetiredEvent` domain event published on asset retire for W7 reassignment flow (#245, #280).
  - **Local disk storage** with type, size and path-traversal checks; `BadRequestException` (400) and `PayloadTooLargeException` (413) in `common.error` (Liviu #90). **S3 + SES adapters** for `prod`, with timeouts and missing-key handling (Matei #129, #177); SES notifications also pushed live to open tabs (#290, #332).
  - **Docker image** (`Dockerfile` at the repo root): builds the React app and serves it from Spring Boot, one origin (ADR-002 ✅, Stefania #111), non-root, health check (Matei #114).
  - **Reservations:** booking + overlap check + row lock, my reservations, cancel, real `ReservationApi`, seeder (Calin #34–#36); availability (#78); admin search, check-out, return (#80); check-out/return windows + scheduled no-show and overdue jobs (#60); `GET /reservations/me` with several `status` values + `GET /reservations/me/summary` (#87); booking-rule boundary tests (#98); two-thread row-lock test, exactly one booking wins (#97); timestamps in whole seconds (#130); no check-out of an overdue or non-bookable asset (#131); My reservations loader fix (#133). OVERDUE bookings block the asset until returned (#141); per-user booking limit checked under an advisory lock (#173); admin cancel reason + `sortDir` on the admin search (#172); huge page offsets → 400 (#153); sweep/reassignment indexes + admin Swagger docs (#162); Swagger examples (#191); frontend fixes: Today button, 14-day horizon, campus clock (Europe/Bucharest), no cancel after the start (#154, #163, #174). **New, not in the original plan:** multi-asset bundle booking `POST /reservations/bundle` (#212), extend an active booking `POST /reservations/{id}/extend` (#213), transfer a booking to a classmate `/reservations/{id}/transfers` (#214), 1-minute slot hold `/reservations/holds` + expiry sweep (#219). Frontend for them: extend + transfer in My reservations (#224), hold countdown in the calendar view (#225). Remaining PIT survivors in reservation tested (#227). Time-zone offsets rejected on reservation endpoints (#233); bundle, transfer and hold security findings from #228 fixed (#234). `ReservationApi.bookBundle` (1-5 assets, all or nothing) for the Projects feature (#235). Same-model substitute suggestions on slot conflict in availability + `CalendarBookingView` (#286). Optional `Idempotency-Key` header on `POST /reservations` and `/reservations/bundle` (sent automatically for `POST /reservations` from `frontend/src/api/reservations.ts`): a retry replays the first answer, FAILED keys can be retried, IN_PROGRESS keys older than 60 s are taken over (Calin #262). `ReservationApi.cancelUpcomingReservationsForUser` + admin `POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming` to cancel a disabled user's future bookings (#266, Refs #229). `isAssetFree` own-hold exclusion, `CalendarBookingView` week-nav loading guard, and hold/bundle/ArchUnit test hardening (Calin #293, Refs #267). Single hold sweep scheduler, unified active overlap queries, and `ReservationHold`/`ReservationService` cleanups (#294, Refs #267). Calendar feed token settings + `IcsWriter` (#299, Refs #289). Calendar feed `GET /api/v1/calendar/{token}.ics` + `CalendarFeedService` + range query (#300, Refs #289). Extracted `HoldService`, `TransferService`, and `ReservationSlotValidator`, and unified `ReservationService` to a single constructor (#296, Refs #267). `ReservationApi.usedMinutesByAsset(from, to)` real check-out usage minutes per asset for Equipment Reliability (#298, Refs #289). Re-check `userApi.findActiveRole` after `lockUserQuota` in `ReservationService`, `HoldService`, and `TransferService` to close the disabled-user booking race (#310, Refs #229); #310 concurrency follow-ups (`FOR UPDATE` in `cancelUpcomingReservationsForUser`, `findByIdForUpdate` in `acceptTransfer`, `pg_stat_activity` polling in `ReservationConcurrencyIT`, #329, Refs #229, #322); `UserDisabledReservationListener` (`AFTER_COMMIT` + `REQUIRES_NEW`, non-`@Async`) cancels upcoming bookings on `UserDisabledEvent` (#302, #330, Refs #229); `cancelReservation` `FOR UPDATE` + deterministic accept/reject lock ITs + transfer lock-order PIT assertions + docs sync (#336); `cancelBundle` (`findByBundleIdForUpdate` ordered by `id`) and `markNoShow` `FOR UPDATE` + `rejectTransfer`-vs-`cancelReservation` and disable-vs-`cancelBundle` race ITs (#338); #338/#346 follow-ups (`markNoShow` locked `userId` notification, `checkOut`-vs-`markNoShow` race IT, `Clock` dates in `ReservationRepositoryTest`, generated IT IDs, #348).
  - **Incidents:** report → asset `UNDER_MAINTENANCE` + `AssetDamagedEvent` (Stefania #40). Triage: start repair, reject, resolve (asset `AVAILABLE` again only when no other open incident is left); `GET /incidents/me`, `GET /incidents/{id}` (#108). Every triage action audited, list indexes (#208). **Photo** upload + view (`/incidents/{id}/photo`, reporter or admin, JPEG/PNG/WebP up to 5 MB, orphan files cleaned up) (#210). Only `HIGH`/`CRITICAL` take the asset out of use at once, `LOW`/`MEDIUM` wait for start repair; max 3 `OPEN` reports per student (#263, #229). Equipment reliability calculator `ReliabilityCalculator` (pure: failures per 100 h of use, compared with the other units of the same model, INSPECT/WATCH levels; Refs #291); admin report `GET /api/v1/admin/reliability?days=` (30–365, default 180) from her incidents, Calin's `usedMinutesByAsset` and the catalog, nothing stored (Refs #291).
  - **Reassignment engine** v1: same-model, then same-category substitute, else force-cancel; `reassignment_log` (Calin #37). Retired assets excluded, ties broken the same way every time (#99). `GET /api/v1/admin/reassignments` (paged log, #96). One transaction per booking, so one failed move no longer rolls back the others (#132). "Moved" / "force-cancelled" badges in My reservations (#100). Candidate lookup outside the lock transaction, retry with the next substitute (#152); same room first in the preventive cascade (#161); #267 review fixes: concurrency-only retry in the engine, holds respected by moves, reservation-then-asset lock order (#270). W7 retire flow: `LocalAssetRetiredListener` on `AssetRetiredEvent` + nullable `reassignment_log.incident_id` migration (#311, Refs #245); follow-up nits: rename `trigger` column to `triggered_by` + generated IDs in `AssetRetiredReassignmentIT` (#326); source-asset guard on `moveToAsset`/`forceCancel` under row lock (#327, Closes #319; removed self-invoking `default` overloads + `CyclicBarrier` race IT, #333).
  - **ArchUnit rules** for module boundaries and coding conventions (Calin #76), plus shared `Clock`, `@Transactional` placement and no ORDINAL enums (#102); method-level `@CrossOrigin`/`@Transactional`, Boot 4 imports, admin prefix (#179); no `default` methods on `..api..` interfaces (#336).
  - **Tests:** `DemoFlowIT` runs the whole demo through the HTTP API and names the broken step and its owner (Matei #181). PIT survivors covered: Matei (#182), Calin (#178, #201, #316). Defense playbook `docs/testing/defense-playbook.md` + test rules in §5 (#183). Team test plan `docs/testing/test-plan.md` with PIT (Calin #101, #199).
  - **Docs:** security chapter `docs/report/security.md` + JWT sequence (Matei #185); UML component + deployment diagrams (#186); reservations/reassignment report chapter (Calin #203), booking sequence diagram (#204), Strategy pattern doc `docs/patterns/strategy-pattern.md` (#205), Observer pattern doc `docs/patterns/observer-pattern.md` (#207). Liviu: `docs/modules/common.md` (#197), catalog/common class + ER diagrams and report chapter (#222).
  - **Frontend** (`frontend/`, Stefania #84–#86): Vite + React + TypeScript + Mantine scaffold, API client (token in `localStorage`, 401 → back to login), login page, layout, role-based routes, shared components; lint + build run in `ci`. **Register page** (#107); token kept when the backend is down on startup (#142); `/admin` index route (#145). Calin's My reservations page + calendar booking view (#87). Matei's admin **Reservations desk** page (#138), sorted by start time on the backend (#194). Liviu's **catalog, asset details and admin inventory** pages (#196; split per tab + shared `statusBadgeColor`, #268, #288), **audit log** page (#221; `PICKUP_PASS_VERIFIED` filter, #288), and **live notification** client + toasts (`src/api/live.ts`, `src/live/LiveToasts.tsx`, #290, #339). Stefania's **My incidents** + Report a problem form with photo (#216) and **admin incident queue** (#223). Calin's quick-book modal on the catalog and asset details + report-incident button (#226). Calin's reservation frontend refactor: shared `bookingRules`, `useSlotHold` hook, and extracted modals (#292, Refs #267); Calin's **Calendar sync** page (#297, Refs #289; opened from a header button on My reservations instead of the student sidebar, #315, Closes #314); `"Show pickup pass"` button on today's `CONFIRMED` rows in My reservations (#328, Refs #322). QR libraries `qrcode.react` + `@yudiel/react-qr-scanner` for Matei's pickup pass PR 3, and `ci.yml` steps no longer skipped by `hashFiles` guards (Stefania, Refs #291, #269). Stefania's **Users** page (`/admin/users`: search, enable/disable, role change) and **Reassignment log** page (`/admin/reassignments`, incident and retired rows) (#331, Closes #305, #306). **Equipment health** page (`/admin/reliability`, admin menu "Equipment": units that keep breaking, 90/180/365 days, INSPECT/WATCH badges, "How is this calculated?" panel; Refs #291). **Incident details** page (`/incidents/:id`, reporter or admin: full description, photo with add/replace while open, history, admin start repair/resolve/reject; the `#` in My incidents, the Incident queue and the Reassignment log links to it).
  - **Projects (#140, Matei, new `project` module, not in the original plan):** design + plan (`docs/superpowers/`, #232); projects as JSON files in `resources/projects/` (10 projects, #239), bilingual RO/EN search without diacritics, `GET /api/v1/projects?q=&lang=` and `GET /api/v1/projects/{slug}` with free units per model (#236). Frontend: Projects page + details page (#238), **Reserve all** `POST /api/v1/projects/{slug}/reserve` (#243), confirmation banner in My reservations (#248, Calin #246). ArchUnit rule: `project` may use only `catalog.api` + `reservation.api` (#235).
- **Tables on `main`:** `users`, `categories`, `lab_rooms`, `assets`, `reservations`, `incident_reports`, `reassignment_log`, `audit_logs`, `reservation_transfers`, `reservation_holds` (`reservations` also has `bundle_id`).
- **AWS (Matei):** Terraform in `infra/`, applied: state bucket, VPC, public subnet, 2 private DB subnets, security groups (#249); private photo bucket, ECR repo `campus-hub`, log group, EC2 instance role, SSM `/campus/*` parameters (#250). RDS is written but off (`db_enabled=false`) until the W7 deploy. First Docker image pushed to ECR on 9 Nov. EC2 t3.small + Elastic IP + CloudFront, RDS on, SES sender, `MAIL_FROM`/`ADMIN_*` parameters: applied 17 Nov, app live on CloudFront (#304). Paused 17 Nov to save credits; RDS auto-restart email alert (#309). `deploy.yml` with GitHub OIDC (#320): after a green `ci` on `main`, build + push to ECR, SSM `campus-deploy <sha>`, health check; deploy role limited to `main`; not applied yet (role + repo variables pending).
- **Open PRs:** none (20 Nov afternoon). Closed without merging: Calin's off-plan #255, #256, #258, #260, #265; his drafts #58, #59, #61 are back on as new PRs (decided 20 Nov, #321, #323: first PR by 30 Nov, all by 7 Dec).
- **Open issues:** #140 (Matei: Projects, left: app-wide RO/EN choice, Stefania), #229 (Stefania: all its PRs merged, can be closed), #268 (Liviu: code read-through), #269 (Stefania: code read-through), #290 (Liviu: live notifications, left: frontend), #291 (Stefania: equipment reliability), #322 (Calin), #324 (Liviu), #325 (Stefania): what's left until the defense (decisions of 20 Nov).
- **M1 ✅:** the full M1 flow was run on a fresh database (API + browser) and `v0.1.0` is tagged on `b81e400` (11 Nov). Next milestone: **M2 `v0.2.0` by Sun 6 Dec**, everything also on AWS. Details per member: `docs/whats-left.md`.
- **Next for Matei:** `terraform apply` for the deploy role (3 to add) + repo variables `AWS_DEPLOY_ROLE_ARN`, `APP_URL`; resume AWS before the teacher feedback demo Mon 23 Nov (fresh image from `main`, demo data); first `deploy.yml` run after the resume; M2 `v0.2.0` by Sun 6 Dec.
- **Prototype demo:** a Swagger run on a fresh database: login → admin creates assets → book → same slot again 409 → report an incident → asset `UNDER_MAINTENANCE` → booking it 409, and its future bookings move to a substitute. **All of it is on `main`**, checked on every build by `DemoFlowIT` (#181). `POST /incidents` also needs `severity` (e.g. `HIGH`).
- Ruleset `protect-main`: no direct push/force push/deletion, PR + 1 approval from any teammate, stale approvals dismissed, conversations resolved, **required checks `ci` and `docs-check`**, branch must be up to date, squash only. `docs-check` (#279) checks the end-of-task docs of section 4 on every PR; required since 10 Nov 2026 (PRs opened before that get it on their next push or branch update).

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
| W7 | Business logic | EC2 + CloudFront first deploy (ADR-002) | Retire flow; audit log endpoint + page | **Reassignment engine v1** + tests | My incidents, incident details, admin incident queue |
| W8 | Business logic | `deploy.yml`, `S3StorageService` | Tests; help Calin with substitute queries | Reassignment log endpoint + notifications; overdue/no-show jobs; concurrency test | Reassignment log view; UI polish |
| W9 | **M2 feature complete** `v0.2.0` | SES, secrets, **admin reservations desk page**; SQS only if ahead | Polish + docs | Full incident → reassignment flow local + AWS | Integration of all screens; prod build |
| W10 | **Feature freeze**: tests & bugs only; everyone: tests for PIT survivors in their module | Alarms, budget, load + security smoke tests | Coverage >= 70% | Integration tests; test plan | Manual E2E checklist, UI polish |
| W11 | Docs & demo | Deployment + component diagrams, security/AWS chapters, runbook | Final class/ER/state diagrams, module docs | State/sequence diagrams, Strategy doc, **test report** | User manual, Observer doc, demo script, 2 rehearsals, **break-it drill** (`docs/testing/defense-playbook.md` §6) |
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
interface UserApi { UserSummary getById(Long id); boolean exists(Long id); Optional<Role> findActiveRole(Long id); }  // findActiveRole: empty if deleted or disabled
record UserSummary(Long id, String email, String fullName, Role role) {}
// common.security (Stefania): a bean that reads the JWT of the current request. Inject it; mock it in service unit tests
@Component class CurrentUser { Long id(); boolean isAdmin(); }   // id() = "sub" claim, isAdmin() = has ROLE_ADMIN
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
  boolean moveToAsset(Long reservationId, Long expectedSourceAssetId, Long newAssetId, String reason);  // re-checks conflict + lock; false if no longer CONFIRMED or no longer on expectedSourceAssetId (null = no check) (#327, #333)
  boolean forceCancel(Long reservationId, Long expectedSourceAssetId, String reason);                   // idempotent; same source check; false if no longer CONFIRMED (#327, #333)
  Optional<ReservationSummary> findCurrentCheckout(Long assetId);
  UUID bookBundle(Long userId, List<Long> assetIds, LocalDateTime start, LocalDateTime end);  // 1-5 assets, all or nothing, counts as 1 booking (#235, for Projects)
  int cancelUpcomingReservationsForUser(Long userId, String reason);  // future CONFIRMED only (+ their pending transfer offers); returns how many; for disabled users (#266, #229)
  Map<Long, Long> usedMinutesByAsset(LocalDateTime from, LocalDateTime to);  // real check-out minutes per asset, for Stefania's reliability page (#298)
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
| Mutation testing (on demand, not in `build`) | `cd backend && ./gradlew pitest` → `backend/build/reports/pitest/index.html` (survived mutants = breaks no test caught) |
| Run every test again (Gradle skips unchanged ones) | `cd backend && ./gradlew test --rerun` |
| Something is broken: what changed? | `git status` · `git diff` · `git diff <last tag>` · `git bisect` (steps in `docs/testing/defense-playbook.md` §1) |
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

Matei owns `AGENTS.md` (and `CLAUDE.md`, which just imports it). Update section 7 (Status) when milestones land: skeleton, CI, entity freeze, each facade (stub -> real), M1, M2, feature freeze. Every member appends their own lines to section 7 in their feature PRs (section 4, End of every task). Anything else outdated: tell Matei or open a small `docs/` PR for him to review. Apart from those section 7 lines, never let an AI edit this file on its own initiative.

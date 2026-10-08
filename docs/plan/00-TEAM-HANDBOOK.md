# Campus Equipment & Incident Hub: Team Handbook (all 4 members)

> **Read this first.** Everyone reads this file fully. Then read your own file (`01-…` to `04-…`).
> It holds the shared rules: project summary, architecture, folder structure, packages, Git rules, conventions, shared contracts, the 12-week plan and the team-wide risks.
> Everything marked **[DECIDE AT KICKOFF]** is a recommended default. Confirm or change it in the first meeting, then write the result in `docs/decisions.md`.

---

## 0. Index of team documents

| File | For whom |
|---|---|
| `00-TEAM-HANDBOOK.md` | Everyone (this file) |
| `01-MEMBER1-MATEI-cloud-security-platform.md` | Member 1: Matei (Cloud, Security, User & Platform) |
| `02-MEMBER2-catalog-inventory.md` | Member 2: Asset Catalog & Inventory |
| `03-MEMBER3-reservations-scheduling.md` | Member 3: Reservations & Scheduling Engine |
| `04-MEMBER4-incidents-reassignment-frontend.md` | Member 4: Incident Hub, Auto-Reassignment & Frontend |

Replace "Member 2/3/4" with real names once you agree on the roles.

---

## 1. Project summary (what we are building)

**Campus Equipment & Incident Hub** is a web platform for university labs. Students reserve shared lab equipment (oscilloscopes, sensor kits, 3D printers, VR headsets…) by time slot. Anyone can report broken equipment with a photo. Lab admins manage the inventory and repairs.

**The problem:** labs track gear on paper or in chat groups. People double-book it, items go missing, and broken equipment gets returned without anyone being told.

**Two main loops:**
1. **Borrowing:** browse the catalog, pick a time slot, and the system rejects overlapping bookings and enforces limits. An admin checks the item out at pickup and checks it in at return.
2. **Incidents:** someone reports a broken asset with a photo. The asset is locked out of booking right away. An admin repairs it and resolves the incident, and the asset becomes available again.

**The standout non-CRUD feature: the Incident Impact & Auto-Reassignment Engine.** When an asset is reported damaged, the system finds every upcoming reservation on it (the "blast radius"). For each one it looks for a healthy substitute with the same model first, then the same category (**Strategy pattern**). If it finds one, it moves the booking there. If not, it force-cancels the booking. Either way the student gets a notification. The trigger is decoupled through events (**Observer pattern**): Spring events locally and Amazon SQS in production.

**Roles in the app:** `STUDENT` (browse, reserve, cancel own, report incidents, see own history) and `ADMIN` (lab staff/professor: manage inventory, check out/in, triage incidents, view audit log and reassignments).

**Hosting:** everyone develops **locally-first** (H2 database, local disk for photos, no AWS account needed). Matei deploys the same code to **AWS**: ECS Fargate, RDS PostgreSQL, S3, SQS, CloudFront and ALB, using the `prod` Spring profile.

**What the professor grades (FIS):** requirements (user stories, use cases), modeling (class, ER, state, sequence, component and deployment diagrams), design patterns, clean architecture, tests, teamwork and Git history, and the live demo. Plan the work around these.

---

## 2. Who does what (summary)

| Member | Owns (code) | Non-code deliverables | Main dependencies |
|---|---|---|---|
| **1: Matei**: Cloud, Security, User & Platform | Project skeleton, `pom.xml`, `config/`, `common/` (errors, time, audit, paging), `user/` (auth, JWT, users), `storage/`, `notification/`, `messaging/` (SQS, prod), Dockerfile, CI/CD, `infra/` (Terraform), dev seeders framework | Component diagram, Deployment (AWS) diagram, security section of the report, CI/CD description | Everyone depends on Matei **early** (skeleton, auth, shared interfaces) |
| **2**: Catalog & Inventory | `catalog/` (Asset, Category, LabRoom, AssetStatus, search/filter, admin CRUD, `CatalogApi` facade, asset locking) | Domain class diagram (lead), ER diagram (lead), Asset state diagram | Depends on Matei (common, security). Members 3 and 4 depend on `CatalogApi` |
| **3**: Reservations & Scheduling | `reservation/` (Reservation, conflict engine, limits, checkout/return, overdue/no-show scheduled jobs, availability endpoint, `ReservationApi` facade) | Reservation state diagram, sequence diagram (booking + checkout), test plan lead | Depends on `CatalogApi`, `UserApi`. Member 4 depends on `ReservationApi` |
| **4**: Incidents, Reassignment & Frontend | `incident/` (IncidentReport, photo upload, triage), `reassignment/` (engine + strategies + log), `frontend/` (React app) | Use case diagram (lead), Strategy and Observer pattern documentation, incident state diagram, user manual and screenshots, demo script lead | Depends on everything (it integrates all modules) |

**Shared by everyone:** user stories for your own module, unit and integration tests for your own code, Swagger annotations on your endpoints, your module's section in the final report, and reviewing other people's PRs.

---

## 3. Architecture overview

### 3.1 Logical architecture (modular monolith)

One Spring Boot application split into **modules (packages)** with strict boundaries. Modules talk to each other only through a small public **`api` package** (a facade interface plus DTO records) or through **events**. A module never touches another module's entities or repositories.

```
                ┌────────────────────────── frontend (React + Vite) ─────────────────────────┐
                │      Login · Catalog · Book · My reservations · Report incident · Admin    │
                └──────────────────────────────────┬─────────────────────────────────────────┘
                                                   │ JSON over HTTP  /api/v1/**  (JWT Bearer)
┌──────────────────────────────────────────────────▼──────────────────────────────────────────┐
│ Spring Boot backend                                                                         │
│                                                                                             │
│  user ◄──────────── reservation ───────────► catalog ◄───────── incident                    │
│   ▲                      ▲                      ▲                  │ publishes              │
│   │                      │ ReservationApi       │ CatalogApi       ▼ AssetDamagedEvent      │
│   │                      └──────────── reassignment ◄──────────────┘ (Spring event / SQS)   │
│                                                                                             │
│  common (errors, Clock, audit, paging)   storage (local | S3)   notification (log | SES)    │
│  config (security, OpenAPI, async)       messaging (SQS bridge, prod only)                  │
└──────────────────────────────────────────────────┬──────────────────────────────────────────┘
                                                   │ JPA / Flyway
                                    H2 (dev, PostgreSQL mode)  |  PostgreSQL on RDS (prod)
```

**Allowed dependency directions (no cycles):**

```
common, config            ← everyone may use
user.api                  ← reservation, incident, reassignment
catalog.api               ← reservation, incident, reassignment
reservation.api           ← reassignment (and optionally incident)
incident.api (events)     ← reassignment
storage, notification     ← incident, reassignment, reservation
catalog                   → must NOT depend on reservation / incident / reassignment
reservation               → must NOT depend on incident / reassignment
```

If you need a dependency that is not on this list, **stop and talk to the team**. It usually means you are about to create a cycle. The usual fix is to move the query into the module that owns the data, or to use an event.

> Example: "show only assets free between 14:00 and 16:00" needs reservation data, so the **availability** endpoint lives in `reservation/`, not in `catalog/`. Catalog must never import reservation.

### 3.2 Physical architecture (production on AWS, owned by Matei)

```
Users → CloudFront ─┬─ /*      → S3 bucket (built React app)
                    └─ /api/*  → ALB (public subnets) → ECS Fargate tasks (Spring Boot, private/public subnets)
                                                         ├→ RDS PostgreSQL (isolated subnets)
                                                         ├→ S3 (incident photos, private)
                                                         ├→ SQS incident-reassignment-queue (+ DLQ)
                                                         ├→ SES (email)  /  Secrets Manager or SSM (secrets)
                                                         └→ CloudWatch Logs
```

Frontend and API share **one origin** (CloudFront), so production needs no CORS. Locally, the Vite dev server proxies `/api` to `localhost:8080`, so local dev needs no CORS either.

---

## 4. Tech stack & versions **[DECIDE AT KICKOFF]**

> Use **exactly** these versions on every laptop. Version mismatches are the #1 cause of "works on my machine".

| Area | Choice | Notes |
|---|---|---|
| Language | **Java 21 (LTS)**, Eclipse Temurin distribution | Install via SDKMAN (Linux/macOS/WSL) or the Temurin installer (Windows). Everyone uses the same major version. |
| Framework | **Spring Boot 4.1.x** (latest patch) | 4.0.x support ends Dec 2026; 4.1.x is supported to mid-2027. **Most online tutorials are still Spring Boot 3, so read section 13.** |
| Build | **Maven** with the **Maven Wrapper** (`./mvnw`) | Nobody installs Maven globally; always use `./mvnw`. |
| DB (dev) | **H2** file database in **PostgreSQL compatibility mode** | Zero install. Data stays in `backend/data/`. |
| DB (prod/CI) | **PostgreSQL 17** (RDS in prod, a service container in CI) | Optional local Postgres via `docker-compose.yml` for anyone who wants it. |
| Migrations | **Flyway** | All tables are created by SQL migration files, never by Hibernate (`ddl-auto=validate`). |
| Security | Spring Security + JWT via `spring-boot-starter-oauth2-resource-server` (HS256) | No hand-written JWT filter needed. Matei owns it. |
| API docs | **springdoc-openapi 3.x** (the line for Boot 4) | Swagger UI at `http://localhost:8080/swagger-ui.html` |
| Tests | JUnit 5, Mockito, AssertJ, Spring Boot test slices, spring-security-test; Testcontainers (optional) | |
| Code style | **Spotless** (palantir-java-format) + `.editorconfig` | Auto-formatting prevents "whole file changed" diffs and merge conflicts. |
| Coverage | JaCoCo | Report for the final presentation. |
| Boilerplate | Lombok (restricted, see 9.3) + Java `record`s for DTOs | |
| Frontend | **React + Vite + TypeScript**, **Node 24 LTS** (`.nvmrc`) | |
| Frontend libs | `react-router` (routing), `axios` (HTTP), `@tanstack/react-query` (server state, optional but recommended), **Mantine** (UI kit + date/time pickers) | Member 4 makes the final call on the UI kit, once, in week 2. |
| Cloud | AWS: ECS Fargate, ECR, RDS, S3, SQS, SES, CloudFront, ALB, VPC, Secrets Manager/SSM, CloudWatch; **Terraform** for IaC | Matei only. Nobody else needs AWS credentials. |
| CI/CD | GitHub Actions | `ci.yml` on every PR, `deploy.yml` on push to `main` (from about week 8) |
| IDE | **IntelliJ IDEA** (free Ultimate with a student license) + VS Code for the frontend if preferred | Enable annotation processing (Lombok). |
| Diagrams | **PlantUML or Mermaid** in `docs/diagrams/` (text files, versioned in Git); **draw.io** for the AWS diagram | Text diagrams merge in Git; images don't. |
| Project mgmt | **GitHub Projects** board + GitHub Issues | One issue per task, linked to PRs. |

### 4.1 Backend dependencies (generated via https://start.spring.io)

Generate the project with **Spring Initializr** (Matei does it once). **Do not copy `<dependency>` blocks from old tutorials.** In Boot 4 several starters were renamed or split out.

Pick these in Initializr: **Spring Web (MVC)**, **Spring Data JPA**, **Validation**, **Spring Security**, **OAuth2 Resource Server**, **Flyway Migration**, **H2 Database**, **PostgreSQL Driver**, **Spring Boot Actuator**, **Lombok**, **Spring Boot DevTools** (optional).

Then add manually:
- `org.springdoc:springdoc-openapi-starter-webmvc-ui` (3.x)
- `org.flywaydb:flyway-database-postgresql` (Flyway needs this extra module for Postgres)
- `org.springframework.security:spring-security-test` (test scope)
- Later (Matei, prod only): AWS SDK v2 BOM + `s3`, `sqs`, `sesv2`, or the **Spring Cloud AWS** starters
- Optional: `org.testcontainers:postgresql`, `com.tngtech.archunit:archunit-junit5` (test scope)
- Plugins: `spotless-maven-plugin`, `jacoco-maven-plugin`

**Rule: only Matei edits `pom.xml`.** Need a library? Open an issue labeled `dependency`. Matei adds it on `main` within a day, and you rebase.

### 4.2 Frontend packages (Member 4 owns `package.json`)

`react`, `react-dom`, `react-router`, `axios`, `@tanstack/react-query`, `@mantine/core`, `@mantine/hooks`, `@mantine/dates`, `@mantine/form`, `@mantine/notifications`, `dayjs`; dev: `typescript`, `vite`, `@vitejs/plugin-react`, `eslint`, `prettier`, `vitest` (optional), `@testing-library/react` (optional).

Commit `package-lock.json`. Use `npm ci` in CI. Don't mix npm, yarn and pnpm.

---

## 5. Repository & project structure

**One monorepo** on GitHub (organization or Matei's account, with all 4 as collaborators): `campus-hub`.

```
campus-hub/
├── .github/
│   ├── workflows/
│   │   ├── ci.yml                    # build + test backend & frontend on every PR (Matei)
│   │   └── deploy.yml                # build image → ECR → ECS; frontend → S3 + CloudFront invalidation (Matei, ~W8)
│   ├── CODEOWNERS                    # auto-requests review from the module owner
│   ├── pull_request_template.md
│   └── ISSUE_TEMPLATE/ (bug.md, task.md, user-story.md)
├── backend/
│   ├── mvnw, mvnw.cmd, .mvn/
│   ├── pom.xml                       # Matei only
│   └── src/
│       ├── main/
│       │   ├── java/com/campus/
│       │   │   ├── CampusHubApplication.java
│       │   │   ├── common/           # Matei: error/, time/ (Clock), audit/, paging/, security/CurrentUser
│       │   │   ├── config/           # Matei: SecurityConfig, OpenApiConfig, AsyncConfig, JacksonConfig
│       │   │   ├── user/             # Matei
│       │   │   ├── storage/          # Matei: StorageService, LocalStorageService, S3StorageService
│       │   │   ├── notification/     # Matei: NotificationService, LoggingNotificationService, SesNotificationService
│       │   │   ├── messaging/        # Matei: SQS bridge + consumer (prod profile only)
│       │   │   ├── catalog/          # Member 2
│       │   │   ├── reservation/      # Member 3
│       │   │   ├── incident/         # Member 4
│       │   │   └── reassignment/     # Member 4
│       │   └── resources/
│       │       ├── application.yml           # shared defaults (Matei)
│       │       ├── application-dev.yml       # H2, local storage, seeders (Matei)
│       │       ├── application-prod.yml      # env-var driven (Matei)
│       │       └── db/migration/             # Flyway SQL files (everyone, see §8)
│       └── test/java/com/campus/...          # mirrors main packages
├── frontend/
│   ├── package.json, package-lock.json, vite.config.ts, tsconfig.json, .nvmrc
│   └── src/
│       ├── api/          # client.ts (axios + JWT), auth.ts, catalog.ts, reservations.ts, incidents.ts, admin.ts
│       ├── auth/         # AuthContext, ProtectedRoute, AdminRoute
│       ├── types/        # TS types mirroring backend DTOs
│       ├── components/   # shared UI pieces
│       ├── pages/
│       │   ├── auth/     # Login, Register
│       │   ├── student/  # Catalog, AssetDetails+Booking, MyReservations, ReportIncident, MyIncidents
│       │   └── admin/    # Inventory, Reservations (checkout/return), IncidentQueue, Reassignments, AuditLog
│       ├── App.tsx (routes), main.tsx
├── infra/                    # Terraform (Matei)
├── docs/
│   ├── decisions.md          # every decision taken + date (short ADR list)
│   ├── requirements/         # user stories, acceptance criteria, business rules, glossary
│   ├── api-contract.md       # endpoints + JSON examples (contract-first)
│   ├── diagrams/             # *.puml / *.mmd / aws.drawio + exported PNGs
│   ├── modules/              # one page per module: what it does, public API, how to test (bus-factor protection)
│   ├── meetings/             # 5-line notes per weekly meeting
│   └── report/               # final FIS report
├── docker-compose.yml        # OPTIONAL local PostgreSQL (Matei)
├── .editorconfig  .gitattributes  .gitignore
├── README.md                 # how to run in 3 commands
└── CONTRIBUTING.md           # short version of §7 and §9
```

### 5.1 Inside every backend module (same layout for all, so code is predictable)

```
com/campus/<module>/
├── api/          # PUBLIC: facade interface (e.g. CatalogApi), summary records, events. The ONLY package other modules may import.
├── domain/       # @Entity classes + enums (+ state transition rules)
├── repository/   # Spring Data JPA interfaces
├── service/      # business logic, @Transactional; implements the api facade
└── web/          # @RestController + request/response DTO records (+ mappers)
```

Optional, recommended from about week 5 (Matei): an **ArchUnit test** that fails the build if a module imports another module's non-`api` package. It enforces the boundaries automatically and looks great in the report.

### 5.2 `.gitignore` must contain (at least)

```
backend/target/  backend/data/  backend/uploads/  *.mv.db  *.trace.db
frontend/node_modules/  frontend/dist/
.idea/  *.iml  .vscode/  .DS_Store  Thumbs.db
.env  *.env.local  infra/.terraform/  *.tfstate  *.tfstate.backup  *.tfvars
```

### 5.3 `.gitattributes` (prevents Windows/Mac/Linux line-ending chaos)

```
* text=auto eol=lf
*.cmd text eol=crlf
*.bat text eol=crlf
*.png binary
*.jpg binary
```

Also run once: `git update-index --chmod=+x backend/mvnw`. Otherwise CI fails with `Permission denied` or `bad interpreter`.

---

## 6. Running the project (target: 3 commands, zero config)

```bash
# backend (dev profile is the default, no env vars needed)
cd backend && ./mvnw spring-boot:run          # http://localhost:8080/swagger-ui.html , /h2-console
# frontend
cd frontend && npm ci && npm run dev          # http://localhost:5173  (proxies /api → :8080)
```

Seed accounts (dev only): `admin@campus.test / password`, `student1@campus.test / password`, `student2@campus.test / password`.

**Reset local DB:** stop the app, delete `backend/data/`, start again. Flyway rebuilds everything and the seeders refill it.

---

## 7. Git rules (non-negotiable)

### 7.1 Workflow: GitHub Flow
- `main` is **always green and runnable**. It is protected: no direct pushes, PR required, **1 approval**, **CI must pass**, branch must be up to date, no force-push.
- One branch per issue, created from fresh `main`:
  `feat/<module>-<short-desc>`, `fix/<module>-<short-desc>`, `test/…`, `docs/…`, `chore/…`, `infra/…`
  e.g. `feat/catalog-asset-search`, `fix/reservation-overlap-edge`, `docs/er-diagram`.
- **Small PRs:** under about 400 changed lines and at most 2–3 days of work. Big PRs don't get reviewed properly and cause conflicts.
- **Sync daily:** `git fetch && git rebase origin/main` on your branch (or merge `main` in if rebasing scares you; pick one style as a team). After rebasing, push with `git push --force-with-lease` (**only on your own branch**).
- **Squash merge** into `main`. The PR title becomes the commit message, so it must follow Conventional Commits.
- Delete the branch after merging.

### 7.2 Commit messages: Conventional Commits
```
feat(catalog): add paginated asset search by category and lab room
fix(reservation): reject bookings where end <= start
test(incident): cover photo type validation
docs(diagrams): add reservation state diagram
chore(build): add springdoc dependency
```
Types: `feat`, `fix`, `test`, `docs`, `refactor`, `chore`, `ci`, `infra`. Scope = module name.

### 7.3 Ownership (enforced by `CODEOWNERS` + review)

| Path | Owner (must approve) | Others may… |
|---|---|---|
| `backend/pom.xml`, `config/`, `common/`, `user/`, `storage/`, `notification/`, `messaging/`, `infra/`, `.github/`, `docker-compose.yml`, `application*.yml` | Matei | request changes via issue |
| `catalog/` | Member 2 | read; use `catalog.api` only |
| `reservation/` | Member 3 | read; use `reservation.api` only |
| `incident/`, `reassignment/` | Member 4 | read; use `incident.api` events only |
| `frontend/` | Member 4 | contribute pages for their own module **after agreeing with Member 4** |
| `db/migration/` | each author for their own files | never edit someone else's merged migration |
| `docs/` | everyone | own sections |

### 7.4 Hard "never" list
- Never commit secrets: passwords, JWT secrets, AWS keys, `.env`. If it happens, tell Matei **immediately**. The secret must be **rotated**; deleting the commit is not enough.
- Never commit `target/`, `node_modules/`, `data/`, `uploads/`, IDE folders.
- Never push to `main` directly. Never force-push `main`.
- Never edit a Flyway migration that is already on `main`. Write a new one.
- Never rename or delete a field from the **frozen entities** (§10) without a team vote. Adding fields is OK.
- Never reformat a whole file by hand or with different IDE settings. Run `./mvnw spotless:apply`.
- Never merge your own PR without approval, even "just a small fix".

### 7.5 Grading-related Git rules
- **Configure Git with your own name and the email of your GitHub account** (`git config --global user.name/user.email`). Professors look at the contributor graph, and commits with a wrong email don't count for you.
- Everyone commits their **own** code. When pairing, the person who typed commits and adds `Co-authored-by: Name <email>`.
- Commit regularly (several times a week). One giant commit in week 11 looks bad and is risky.
- Tag milestones: `v0.1.0` (M1), `v0.2.0` (M2), `v1.0.0` (final).

### 7.6 PR checklist (put in `pull_request_template.md`)
- [ ] Linked issue (`Closes #12`)
- [ ] `./mvnw verify` passes locally (tests + spotless check)
- [ ] New logic has tests (happy path + at least 1 failure case)
- [ ] Swagger annotations / example on new endpoints
- [ ] New migration file? Version number checked against `main` (§8.2)
- [ ] No entity returned from a controller (DTOs only)
- [ ] `docs/api-contract.md` updated if the API changed
- [ ] Screenshots (frontend PRs)

### 7.7 Reviewing
- Review within **24h** of being requested. Unreviewed PRs block people.
- Review for: correctness, tests, module boundaries, naming, obvious security issues (ownership checks!). Don't nitpick formatting; Spotless handles it.
- Beginner-friendly tone. Ask questions ("what happens if end < start?") instead of making demands.

---

## 8. Database rules

### 8.1 General
- Tables are created **only** by Flyway migrations in `backend/src/main/resources/db/migration/`. Hibernate runs with `spring.jpa.hibernate.ddl-auto=validate`, so it checks that entities match the tables and **fails at startup** if they don't. That is good: you find mistakes immediately.
- Naming: tables plural snake_case (`assets`, `lab_rooms`, `reservations`, `incident_reports`); columns snake_case (`start_time`, `asset_id`); Java fields camelCase (Spring maps them automatically).
- Primary keys: `id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`. In Java: `@GeneratedValue(strategy = GenerationType.IDENTITY)`.
- **Write SQL that works on BOTH H2 and PostgreSQL.** Allowed types: `BIGINT`, `INTEGER`, `VARCHAR(n)`, `TEXT`, `BOOLEAN`, `TIMESTAMP`, `DATE`, `NUMERIC(p,s)`. **Avoid** `SERIAL`, `JSONB`, arrays, partial indexes, Postgres-only functions, `ENUM` types (store enums as `VARCHAR(32)` + `@Enumerated(EnumType.STRING)`). **Never use `EnumType.ORDINAL`**: reordering an enum would silently corrupt the data.
- Real foreign keys in SQL (`REFERENCES assets(id)`) even across modules. The database protects integrity, and the ER diagram shows real relationships.
- Indexes on columns you search/filter by (e.g. `reservations(asset_id, start_time)`).
- CI runs migrations + tests against **real PostgreSQL**, so H2/Postgres differences are caught before merge.

### 8.2 Flyway file naming: avoiding the #1 collision
Two people creating `V3__…` at the same time breaks startup ("Found more than one migration with version 3").

**Rule [DECIDE AT KICKOFF]: timestamp versions.**
`V2026_10_20_1430__catalog_create_assets.sql` (`V` + `YYYY_MM_DD_HHMM` + `__` + module + description).
Collisions become practically impossible, and the order follows creation time.

- Before merging: rebase on `main`. If someone else's migration with a **later** timestamp is already on `main` and yours is earlier, rename yours to the current time. Otherwise Flyway refuses to run it on databases that already applied the later one (`Detected resolved migration not applied to database`).
- Dev tip: if Flyway complains locally after switching branches, delete `backend/data/` and restart.
- **Never modify a merged migration.** Checksum mismatch breaks every other laptop and production. To change a table, write a new `ALTER TABLE` migration.
- One migration = one logical change, owned by one person.

### 8.3 Seed data (dev only)
Each module has a `@Profile("dev")` seeder (`CommandLineRunner`) that runs only if its tables are empty, ordered with `@Order`: user (1) → catalog (2) → reservation (3) → incident (4). Matei provides the pattern and the user seed. Seed enough data for a convincing demo: about 4 categories, 3 lab rooms, 15–20 assets (with duplicates of the same model, so reassignment has substitutes!), a few past and future reservations, 1–2 incidents.

### 8.4 JPA rules for beginners
- `@ManyToOne(fetch = FetchType.LAZY)` **always** (the default is EAGER, which is slow and surprising).
- Avoid `@OneToMany` collections unless really needed. Query through the repository instead (`findByAssetId`).
- **Cross-module references are plain IDs in Java** (`private Long assetId;` in `Reservation`), with a real FK in SQL. Inside a module, `@ManyToOne` is fine (`Asset → Category`). This keeps modules decoupled, avoids JSON infinite recursion and avoids lazy-loading surprises.
- `spring.jpa.open-in-view=false` (Matei sets it). Map entities to DTOs **inside** `@Transactional` service methods. Otherwise you get `LazyInitializationException`.
- `@Transactional` goes on **service** methods (not controllers, not repositories). Read-only queries: `@Transactional(readOnly = true)`.
- `@Transactional` does **not** work when a method calls another method of the same class (self-invocation), or on `private` methods.

---

## 9. Code conventions

### 9.1 API conventions
- Base path `/api/v1`. Admin-only endpoints **must** live under `/api/v1/admin/**`. Security protects that prefix automatically, so nobody edits `SecurityConfig`.
- Nouns, plural: `/assets`, `/reservations`, `/incidents`. Actions that change state are sub-resources: `POST /api/v1/reservations/{id}/cancel`, `POST /api/v1/admin/reservations/{id}/checkout`.
- JSON camelCase. Dates and times: ISO-8601 (`"2026-11-03T14:00:00"`). IDs: numbers.
- Status codes: `200` OK, `201` created (+ body), `204` no content, `400` validation error, `401` not logged in, `403` not allowed, `404` not found, `409` conflict (double booking, invalid state transition, limit reached).
- Errors: **RFC 9457 `ProblemDetail`** JSON produced by one global `@RestControllerAdvice` (Matei), e.g.
  ```json
  { "type":"about:blank", "title":"Reservation conflict", "status":409,
    "detail":"Asset 12 is already booked between 14:00 and 16:00", "code":"RESERVATION_CONFLICT" }
  ```
  You throw the shared exceptions from `common.error` (`NotFoundException`, `ConflictException`, `BusinessRuleException(code, msg)`, `ForbiddenException`). **Never** build error responses by hand in controllers. The `code` values are listed in `docs/api-contract.md`, so the frontend can show friendly messages.
- Lists: paginated with `?page=0&size=20&sort=name,asc`, returned as the shared `PageResponse<T>` record (`content, page, size, totalElements, totalPages`). Don't return Spring's `Page` directly; its JSON shape is not stable.
- **Ownership checks in the service layer.** A student may only see or cancel **their own** reservations and incidents. The user ID always comes from the JWT (`CurrentUser.id()`), **never** from the request body. Return `404` (not `403`) for other people's resources, so you don't leak which IDs exist.

### 9.2 Java conventions
- Controllers are thin: validate input (`@Valid`), call the service, map to a response DTO. No business logic in controllers.
- **Never return or accept `@Entity` objects in controllers.** DTOs are `record`s in `web/` (`CreateReservationRequest`, `ReservationResponse`). Mapping is a static method (`ReservationResponse.from(reservation)`) or a small mapper class. No MapStruct.
- Validation annotations on request records: `@NotNull`, `@NotBlank`, `@Size`, `@Future`, `@Positive`…
- Constructor injection only (`@RequiredArgsConstructor` + `private final`). Never `@Autowired` on fields.
- **Time:** never call `LocalDateTime.now()` directly. Inject the shared `Clock` bean (zone **Europe/Bucharest**) and use `LocalDateTime.now(clock)`. The AWS servers run in UTC, so `now()` would be 2–3 hours off and "you can't book in the past" breaks. It also makes tests deterministic (fixed clock).
- Use `Instant`/`LocalDateTime` consistently: **[DECIDE AT KICKOFF]** recommended `LocalDateTime` meaning "campus local time" for reservation slots, `Instant` for audit timestamps.
- Names: classes `PascalCase`, methods and fields `camelCase`, constants `UPPER_SNAKE`, packages lowercase. Tests are `XxxServiceTest` (unit) and `XxxControllerIT` / `XxxIntegrationTest` (integration). Test method names describe behavior: `rejectsOverlappingReservation()`.
- Logging: `private static final Logger log = LoggerFactory.getLogger(...)` (or Lombok `@Slf4j`). Log business events at INFO, never log passwords or tokens.

### 9.3 Lombok rules
Allowed: `@Getter`, `@Setter`, `@NoArgsConstructor(access = PROTECTED)` on entities, `@RequiredArgsConstructor` on services, `@Slf4j`.
**Forbidden on entities:** `@Data`, `@EqualsAndHashCode`, `@ToString`, `@AllArgsConstructor`. They trigger lazy loading, infinite recursion and broken `Set` behavior. Records don't need Lombok.

### 9.4 Tests (everyone)
- **Unit tests** for services with Mockito (no Spring context, fast). This is where business rules are tested.
- **Repository tests** with `@DataJpaTest` for custom queries (especially the overlap query!).
- **Web tests** with `@WebMvcTest` + `@WithMockUser(roles = "ADMIN")` for status codes and security on your endpoints.
- A few **full integration tests** with `@SpringBootTest` for the critical flows: book → checkout → return; report incident → reassignment happened.
- In Boot 4, `@MockBean` is gone. Use **`@MockitoBean`**.
- Target: about 70%+ line coverage on `service/` packages (JaCoCo). Every bug fixed gets a test first.

### 9.5 Frontend conventions (summary, details in Member 4's file)
- All HTTP calls go through `src/api/client.ts` (one axios instance: base URL `/api/v1`, adds `Authorization: Bearer`, on `401` clears the token and redirects to login).
- TS types in `src/types/` mirror backend DTOs exactly. They are updated in the **same PR** as the backend DTO change (or immediately after).
- No hardcoded `http://localhost:8080` anywhere. Use relative `/api/...` (Vite proxy in dev, CloudFront in prod).

---

## 10. Shared domain model: the **Entity Freeze** (end of week 2 / start of week 3)

One 2-hour session with all 4 members creates the skeleton entities + first migrations in a **single PR**. After that merge: **add fields freely, never rename/remove without a vote.**

> ⚠️ Correction vs. the Gemini plan: **Asset status must NOT contain `RESERVED` or `IN_USE`.** An asset reserved for Friday is still physically available on Monday. Storing reservation state on the asset creates two sources of truth that will drift apart. Asset status is **operational only**. Whether an asset is "free at a given time" is **computed** from reservations.

```
users            (id, email UNIQUE, password_hash, full_name, role[STUDENT|ADMIN], enabled, created_at)
categories       (id, name UNIQUE, description)
lab_rooms        (id, code UNIQUE, name, building)
assets           (id, name, model, serial_number UNIQUE, description, status, category_id FK, lab_room_id FK,
                  created_at, updated_at, version)
                  status ∈ AVAILABLE | UNDER_MAINTENANCE | RETIRED
reservations     (id, user_id FK, asset_id FK, original_asset_id FK NULL, start_time, end_time, status,
                  created_at, checked_out_at NULL, returned_at NULL, cancelled_at NULL, cancel_reason NULL, version)
                  status ∈ CONFIRMED | CHECKED_OUT | RETURNED | CANCELLED | FORCE_CANCELLED | OVERDUE | NO_SHOW
incident_reports (id, asset_id FK, reported_by_id FK, reservation_id FK NULL, description, severity[LOW|MEDIUM|HIGH],
                  photo_key NULL, status, created_at, resolved_at NULL, resolved_by_id FK NULL, resolution_note NULL)
                  status ∈ OPEN | IN_REPAIR | RESOLVED | REJECTED
reassignment_log (id, incident_id FK, reservation_id FK, from_asset_id FK, to_asset_id FK NULL,
                  outcome[REASSIGNED|FORCE_CANCELLED], strategy, created_at)
audit_log        (id, actor_id NULL, action, entity_type, entity_id, details, created_at)
```

Notes:
- `assets.model` is **required** for the `SameModelStrategy` of the reassignment engine (Members 2 and 4: don't forget it).
- `reservations.original_asset_id` + `reassignment_log` record auto-reassignment. A reassigned reservation **stays `CONFIRMED`** (it is still a live booking). **[DECIDE AT KICKOFF]** If you prefer a separate `AUTO_REALLOCATED` status for the diagram, then it **must** be counted as an "active" status everywhere (conflict checks, limits, overdue jobs). That is a common source of double-bookings. Recommended: keep `CONFIRMED` + the log.
- `version` columns enable optimistic locking (`@Version`) on rows that can be edited concurrently.
- "Active" reservation statuses (block the slot and count toward limits): `CONFIRMED`, `CHECKED_OUT`, `OVERDUE`. Define this **once** as a constant in the reservation module.

### 10.1 State machines (go into the report as state diagrams)

```
Reservation:  CONFIRMED ──checkout──► CHECKED_OUT ──return──► RETURNED
                 │  │                      │
                 │  │                      └─(end_time passed, job)──► OVERDUE ──return──► RETURNED
                 │  ├─(student/admin cancel before start)──► CANCELLED
                 │  ├─(asset damaged, no substitute)──────► FORCE_CANCELLED
                 │  └─(not picked up within grace period, job)──► NO_SHOW
                 └─(asset damaged, substitute found) → stays CONFIRMED with new asset_id

Asset:        AVAILABLE ◄──resolve/reject last open incident── UNDER_MAINTENANCE
                 │    └──────────incident reported────────────────►┘
                 └──admin retire──► RETIRED (terminal)

Incident:     OPEN ──start repair──► IN_REPAIR ──resolve──► RESOLVED
                └──reject (false report)──► REJECTED
```

Implement transitions **in one place** per entity (e.g. `reservation.checkOut(now)`, which throws `InvalidStateTransitionException` → 409 if the current status doesn't allow it). Never do scattered `setStatus(...)` calls from services.

### 10.2 Business rules: agree on numbers in week 2 **[DECIDE AT KICKOFF]**

| Rule | Proposed value | Owner |
|---|---|---|
| Max active reservations per student | 2 | M3 |
| Max booking horizon | 14 days ahead | M3 |
| Min / max reservation length | 30 min / 4 h | M3 |
| Slot granularity | start/end on :00 or :30 | M3 |
| Lab opening hours | Mon–Fri 08:00–20:00 | M3 |
| Can't book in the past / with end ≤ start | always | M3 |
| Student cancellation | until start time | M3 |
| Checkout window | from 15 min before start until 30 min after start | M3 |
| No-show | not checked out 30 min after start → `NO_SHOW` (job) | M3 |
| Overdue | `CHECKED_OUT` and `end_time` passed → `OVERDUE` (job) + notification | M3 |
| Admins | bypass the per-student limit, not the conflict check | M3 |
| Only `AVAILABLE` assets can be booked | always | M2/M3 |
| Reporting an incident | any logged-in user; asset → `UNDER_MAINTENANCE` immediately | M4 |
| Reassignment scope | all future active reservations of the asset (not only 7 days) | M4 |
| Substitute search order | same model → same category (+ same lab room preferred) | M4 |
| Photo | optional, max 5 MB, JPEG/PNG/WebP only | M4/Matei |
| Retiring an asset with future bookings | treated like damage → triggers reassignment (stretch goal) | M2/M4 |

---

## 11. Cross-module contracts (agree in week 2, stubs merged in week 3)

Each owner publishes an **interface** in their `api/` package early. A stub implementation that returns fake data is fine, so others can code against it before the real logic exists. Signatures may be extended later; don't break existing ones without telling the consumers.

```java
// user.api (Matei)
interface UserApi { UserSummary getById(Long id); boolean exists(Long id); }
record UserSummary(Long id, String email, String fullName, Role role) {}
// common.security (Matei)
CurrentUser.id(); CurrentUser.isAdmin();     // reads the JWT of the current request

// catalog.api (Member 2)
interface CatalogApi {
  AssetSummary getAsset(Long assetId);                                   // throws NotFoundException
  boolean isBookable(Long assetId);                                      // status == AVAILABLE
  List<AssetSummary> findAvailableByModel(String model, Long excludeAssetId);
  List<AssetSummary> findAvailableByCategory(Long categoryId, Long excludeAssetId);
  void markUnderMaintenance(Long assetId);
  void markAvailable(Long assetId);
  void lockForBooking(Long assetId);                                     // pessimistic row lock, call inside @Transactional
}
record AssetSummary(Long id, String name, String model, Long categoryId, Long labRoomId, AssetStatus status) {}

// reservation.api (Member 3)
interface ReservationApi {
  List<ReservationSummary> findUpcomingActiveByAsset(Long assetId, LocalDateTime from);
  boolean isAssetFree(Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId);
  void moveToAsset(Long reservationId, Long newAssetId, String reason);  // re-checks conflict + lock
  void forceCancel(Long reservationId, String reason);
  Optional<ReservationSummary> findCurrentCheckout(Long assetId);
}
record ReservationSummary(Long id, Long userId, Long assetId, LocalDateTime start, LocalDateTime end, ReservationStatus status) {}

// incident.api (Member 4)
record AssetDamagedEvent(Long incidentId, Long assetId, Long reportedById, Instant occurredAt) {}

// storage (Matei)
interface StorageService { String store(InputStream in, long size, String contentType, String ext); Resource load(String key); void delete(String key); }
// notification (Matei)
interface NotificationService { void notifyUser(Long userId, String subject, String body); }
// common.audit (Matei)
interface AuditService { void record(Long actorId, String action, String entityType, Long entityId, String details); }
```

### 11.1 Event flow (Observer pattern)

```
dev / test profile:
IncidentService.report() ─ saves incident, calls catalogApi.markUnderMaintenance(), publishes AssetDamagedEvent
        └─ after COMMIT ─► @TransactionalEventListener in reassignment (async) ─► ReassignmentService.handle(event)

prod profile:
IncidentService.report() ─ publishes AssetDamagedEvent (same code!)
        └─ after COMMIT ─► messaging.SqsBridge sends JSON to SQS ─► SqsConsumer ─► ReassignmentService.handle(event)
```

- The incident module **does not know** whether SQS exists. Only Matei's `messaging` module (prod) or the local listener (non-prod) reacts. Use `@Profile` so **exactly one** of them is active. Otherwise reassignment runs twice.
- Use `@TransactionalEventListener(phase = AFTER_COMMIT)`, **not** plain `@EventListener`. Plain listeners run inside the incident transaction: if reassignment crashes, the incident report is rolled back too, and in the async case the listener may not see the uncommitted incident yet. The handler that writes data needs its own transaction (`@Transactional(propagation = REQUIRES_NEW)` or a separate service method with `@Transactional`).
- `ReassignmentService.handle()` must be **idempotent**: SQS can deliver the same message twice. Re-running for the same incident must not move bookings twice. That holds naturally if it only processes `CONFIRMED` reservations still on the damaged asset.

---

## 12. The 12-week plan (team level)

> Fill in real dates: W1 = ____. **Check the university calendar.** If W1 is early October, weeks 11–12 hit the **Christmas holidays**. Plan the final freeze **before** the break, or confirm the defense date now.

| Week | Team goal | Matei | Member 2 | Member 3 | Member 4 |
|---|---|---|---|---|---|
| **W1** | Kickoff, tools, learning | Create repo, branch protection, generate skeleton, CI (build+test), README; install guide | Tools installed; Spring tutorials (§14); first practice PR | same | same + React/Vite tutorial |
| **W2** | Requirements & contracts | Auth design, security rules, error format, `common` package design | User stories catalog; class/ER draft | User stories reservations; business rules numbers | Use case diagram; user stories incidents; UI wireframes; pick UI kit |
| **W3** | **Entity freeze**, foundations | Login/register + JWT working; `Clock`, errors, `PageResponse`, `CurrentUser`; stubs for Storage/Notification/Audit; seeders pattern | Catalog entities + migration + `CatalogApi` stub | Reservation entity + migration + `ReservationApi` stub | Incident entity + migration; frontend scaffold, login page, API client |
| **W4** | Core CRUD | Spotless + JaCoCo in CI; ArchUnit rule; Terraform: VPC, RDS, S3 | Admin CRUD categories/rooms/assets; tests | Create/cancel reservation + **overlap check** + tests | Report incident (no photo yet) + asset → maintenance; catalog page |
| **W5** | Core CRUD | `LocalStorageService`; notification logging impl; audit impl; Postgres in CI | Search/filter + pagination; `CatalogApi` real impl | Limits + validation rules; my-reservations; availability endpoint | Photo upload; admin incident queue; booking UI |
| **W6** | **M1: walking skeleton** (`v0.1.0`) | Dockerfile; ECR; help integration | Asset details; locking; seed data | Admin checkout/return + state transitions | My reservations UI, admin pages wiring |
| **W7** | Business logic | ECS + ALB + CloudFront first deploy | Retire asset flow; edge cases | Overdue/no-show `@Scheduled` jobs | **Reassignment engine v1** (event + strategies) |
| **W8** | Business logic | `deploy.yml` (CI/CD to AWS); S3StorageService | Tests + help M4 with substitute queries | `moveToAsset` / `forceCancel` hardened; concurrency test | Reassignment log + notifications; admin views |
| **W9** | **M2: feature complete** (`v0.2.0`) | SQS bridge + consumer + DLQ; SES; secrets | Polish + docs | Polish + docs | Integration of all screens |
| **W10** | **Feature freeze**: tests & bugs only | CloudWatch alarms, budget check, load smoke test | Tests ≥70% service coverage | Integration tests of full flows | E2E manual test pass; UI polish |
| **W11** | Documentation & demo | Deployment diagram, AWS section, runbook | Class/ER final; module doc | State/sequence diagrams; test report | User manual; demo script; rehearsal ×2 (local + AWS) |
| **W12** | Buffer + defense (`v1.0.0`) | Keep AWS up for demo; then cost shutdown | Q&A prep | Q&A prep | Q&A prep |

**Milestone definitions:**
- **M1 (end W6):** locally a student can register, log in, browse assets, book a slot (conflicts rejected), see "my reservations". An admin can add assets. CI green. Swagger shows all endpoints.
- **M2 (end W9):** everything in §1 works locally **and** on AWS, including incident with photo → automatic reassignment → notification.
- **Feature freeze (start W10):** after this, only bug fixes, tests, docs. **No new features**, however tempting.

### 12.1 Weekly rhythm
- **One 30–45 min meeting per week** (same day/time): each person answers *done / next / blocked*. Update the board. Write 5 lines in `docs/meetings/`.
- **Async daily check-in** in the group chat (one line). If you are blocked for **more than 2 hours, ask**. Beginners lose days by staying silent.
- Each weekend: everyone pulls `main` and runs the app. If `main` is broken, fixing it is priority #1 for whoever broke it.

### 12.2 Definition of Done (for any task)
Code on `main` via a reviewed PR · CI green · tests for the logic · endpoint visible and documented in Swagger · works from a fresh DB (delete `data/`) · API contract / docs updated · issue closed.

---

## 13. Spring Boot 4 vs. old tutorials (READ THIS, everyone)

Most tutorials, Stack Overflow answers and even AI answers are for **Spring Boot 2/3**. In Boot 4 (Spring Framework 7, Spring Security 7, Jackson 3) things changed. If something from a tutorial does not compile, check this list first:

| You see in a tutorial | Use instead |
|---|---|
| `javax.persistence.*`, `javax.validation.*` | `jakarta.persistence.*`, `jakarta.validation.*` |
| `extends WebSecurityConfigurerAdapter` | a `@Bean SecurityFilterChain` (Matei owns this anyway) |
| `.antMatchers(...)`, `.mvcMatchers(...)`, `.authorizeRequests()` | `.requestMatchers(...)`, `.authorizeHttpRequests(...)` |
| `@MockBean` | `@MockitoBean` |
| Copy-pasted `<dependency>` with explicit versions | Let Initializr/Boot manage versions; ask Matei |
| `springdoc-openapi` 1.x/2.x | 3.x (Boot 4 line) |
| `com.fasterxml.jackson.databind.ObjectMapper` imports | Jackson 3 uses `tools.jackson.*` packages (annotations like `@JsonIgnore` stay in `com.fasterxml.jackson.annotation`) |
| `jjwt` 0.9/0.11 code (`Jwts.parser().setSigningKey`) | We use Spring's resource server, no jjwt needed |
| `spring.jpa.hibernate.ddl-auto=update` | We use Flyway + `validate` |

**AI tools (ChatGPT, Gemini, Claude, Copilot) are allowed, but:** you must be able to **explain every line you commit**. At the defense the professor *will* ask "why did you do this?". Tell the AI explicitly "Spring Boot 4.1, Java 21, Jakarta". Never paste secrets into an AI tool.

---

## 14. Learning path (W1–W2, everyone, ~6–8h total)

1. Java refresher: records, enums, `Optional`, streams, `LocalDateTime` (2h).
2. spring.io guides: **"Building a RESTful Web Service"**, **"Accessing Data with JPA"**, **"Validating Form Input"** (3h).
3. SQL basics: `SELECT/WHERE/JOIN/GROUP BY`, primary/foreign keys (1–2h). Play in the H2 console.
4. Git: branches, rebase, resolving a conflict. Do one practice conflict as a team in W1 (1h).
5. HTTP + JSON + REST status codes; try endpoints in Swagger UI (30 min).
6. Member 4 (+ anyone curious): React official tutorial + Vite quick start.

Pattern to memorize: **Controller → Service → Repository → Entity**, DTO at the edges.

---

## 15. FIS deliverables checklist (verify with the professor!)

- [ ] Vision / problem statement (§1)
- [ ] Requirements: user stories with acceptance criteria (Given/When/Then), non-functional requirements (security, performance, availability)
- [ ] Use case diagram (+ 3–4 detailed use case descriptions)
- [ ] Domain class diagram; ER diagram
- [ ] State diagrams (Reservation, Asset, Incident)
- [ ] Sequence diagrams (booking with conflict check; incident → reassignment)
- [ ] Component diagram (modules + dependencies, §3.1); Deployment diagram (AWS, §3.2)
- [ ] Design patterns explained: Strategy (substitute selection), Observer (events), Facade (module APIs), Repository, DTO, (State-like transitions), Dependency Injection
- [ ] Test plan + test report (what is tested, coverage %)
- [ ] Project management evidence: board, sprints, meeting notes, Git history, decisions log
- [ ] User manual (screenshots), install/run guide (README)
- [ ] Demo script + slides

**Questions to ask the professor/TA in W1:** required documents and format/language (RO/EN)? Checkpoint dates? Is a specific methodology required (Scrum, UP)? Is a specific UML tool required? Is cloud deployment valued, or is a local demo enough? How is individual contribution graded? Are AI tools allowed and must they be declared?

---

## 16. Team-wide risks: what can go wrong and what we do about it

| # | Risk | Early warning sign | Prevention | If it happens |
|---|---|---|---|---|
| 1 | **Beginner overload** (Spring + DB + Git + React at once) | Someone silent for days, PRs not appearing | §14 learning path, small tasks, pair sessions, "ask after 2h stuck" | Pair program; Matei unblocks (explains, doesn't take over) |
| 2 | **Outdated tutorial code** doesn't compile | `javax`, `WebSecurityConfigurerAdapter`, `@MockBean` errors | §13 table | Check §13 first, then ask |
| 3 | **Merge conflicts** | Two people editing the same file | Module ownership, CODEOWNERS, small PRs, daily rebase, Spotless | Resolve together on a call, never "accept mine" blindly |
| 4 | **Broken `main`** | CI red, app doesn't start after pull | Branch protection + CI required | Whoever broke it fixes or reverts within hours |
| 5 | **Entity/contract changes break others** | Compile errors after pulling | Entity freeze, `api` facades, "add, don't rename" | Revert, discuss, change with a deprecation step |
| 6 | **Flyway collisions / checksum errors** | `Validate failed`, duplicate version | §8.2 timestamp naming, never edit merged migrations | Delete local `data/`; for `main`, write a fix-forward migration |
| 7 | **H2 vs PostgreSQL differences** | Works locally, fails in CI/AWS | Portable SQL (§8.1), CI on Postgres | Fix the SQL to the common subset |
| 8 | **Time zone bugs** | Bookings shifted 2–3h on AWS | Shared `Clock` with Europe/Bucharest; tests with fixed clock | Search for `.now()` without clock |
| 9 | **Uneven workload** (Member 4 has the most) | Frontend lagging by W6 | Backend owners write their `src/api/*.ts` + types; others help with simple pages after M1 | Re-split pages at the W6 retro |
| 10 | **Integration left to the end** | Modules "done" but never run together | M1 walking skeleton in W6, everyone runs `main` weekly | Integration day with all 4 on a call |
| 11 | **Scope creep** | "Let's also add QR codes / chat / mobile app" | Feature freeze W10; stretch list in `docs/decisions.md` | Say "after v1.0.0" |
| 12 | **Someone drops out or disappears** (bus factor) | Missed meetings, no commits | `docs/modules/<module>.md` kept current; reviews spread knowledge | Redistribute by module; inform the professor early |
| 13 | **AWS costs / account problems** | Bill > $0, free-tier warnings | Matei: budgets & alarms, destroy when idle, local demo always works | Shut down, demo locally |
| 14 | **Demo failure** (Wi-Fi, AWS, laptop) | — | Rehearse twice; local fallback; seed data; recorded backup video | Switch to local, or play the video |
| 15 | **Can't explain own code at defense** | Code pasted from AI | Every PR reviewed and explained; each member presents own module | — |
| 16 | **Secrets leaked to GitHub** | `.env`, keys in diff | `.gitignore`, review, GitHub secret scanning on | Rotate immediately, tell Matei |
| 17 | **Holidays / exam session** | Calendar | Freeze before the break | — |
| 18 | **Different OS problems** (Windows CRLF, WSL paths, Mac ARM) | `bad interpreter`, `exec format error`, weird diffs | `.gitattributes`, `.editorconfig`, Docker `--platform linux/amd64` | See Matei's file |
| 19 | **Security holes in business logic** (IDOR) | Student can cancel someone else's booking via Swagger | Ownership checks from JWT (§9.1), tests for them | Add the check + a test |
| 20 | **Double booking under concurrency** | Two simultaneous requests both succeed | Row lock (`lockForBooking`) inside the transaction | Concurrency test (Member 3) |

---

## 17. Kickoff meeting agenda (90 min)

1. Read §1–§2 together; confirm the roles and who is Member 2/3/4 (10 min)
2. Go through all **[DECIDE AT KICKOFF]** items and record them in `docs/decisions.md` (20 min)
3. Agree the meeting day/time, chat channel, and the review-within-24h rule (5 min)
4. Fill in the real dates for W1–W12; check holidays and defense date (10 min)
5. Everyone: GitHub username to Matei; install JDK 21, IntelliJ, Node 24, Git; set git name/email (20 min, start now)
6. Create the first issues on the board for W1–W2 (15 min)
7. Assign the question list for the professor (§15) to one person (5 min)
8. Practice: everyone opens one tiny PR (add your name to README) before the end of the week (5 min)

---

## 18. Stretch goals (only after M2, only if ahead of schedule)

QR code on each asset for quick check-out · email reminders before a booking starts · equipment usage statistics for admins (most-booked, damage rate per model) · waitlist on fully-booked slots · `LAB_ASSISTANT` role · calendar view · retire-asset triggering reassignment · Spring Modulith instead of ArchUnit.

# Campus Equipment & Incident Hub

University project for **Fundamentele Ingineriei Software (FIS)**.
Students reserve shared lab equipment by time slot and report broken equipment with a photo.
When an asset is reported damaged, an **auto-reassignment engine** moves its upcoming bookings to a substitute (or cancels them) and notifies the students.

## ▶ Quick start: run it, open it, test it

Needs JDK 25, PostgreSQL 18 with the `campus` user and databases, and Node.js 24 (first time? sections 1 and 1.1).

**1. Start PostgreSQL and check it answers**

```bash
sudo service postgresql start      # Linux/WSL, after every reboot (Windows/macOS: it starts by itself)
pg_isready -h localhost            # → "localhost:5432 - accepting connections"
```

**2. Start the backend** (terminal 1, http://localhost:8080)

```bash
cd backend
./gradlew bootRun                  # Windows: gradlew.bat bootRun. Wait for "Started CampusHubApplication"
```

**3. Start the frontend** (terminal 2, http://localhost:5173)

```bash
cd frontend
npm ci                             # first time, or after package.json changed
npm run dev                        # sends every /api call on to :8080, so start the backend first
```

**4. Open it**

| Address | What it is | You should see |
|---|---|---|
| <http://localhost:5173> | **The app** (React) | The login page. Log in with an account below |
| <http://localhost:8080/swagger-ui.html> | **Swagger**: try every API endpoint by hand | All endpoints. Log in with `POST /api/v1/auth/login`, copy `accessToken`, click **Authorize**, paste it |
| <http://localhost:8080/actuator/health> | Is the backend up? | `{"status":"UP"}` |
| <http://localhost:8080/api/v1/auth/ping> | Does security let public calls through? | A short JSON message |
| pgAdmin → `localhost:5432`, database `campus`, user `campus` / `campus` | The data itself | The tables (`users`, `assets`, `reservations`, …) |

**Dev accounts** (password `password` for all): `student1@campus.test`, `student2@campus.test`, `admin@campus.test`. On an empty database the app also creates demo equipment (categories, lab rooms, several assets of the same model) and a few bookings.

**What works in the browser today:** login, **My reservations** with the booking calendar (students), the **Reservations desk** for pickups and returns (admin, `/admin/reservations`). The catalog, incident and inventory pages are still placeholders (open PRs); use Swagger for those.

**5. Run the tests**

| Command (in `backend/`, PostgreSQL running) | What it checks |
|---|---|
| `./gradlew build` | Everything: all tests + format check + coverage. Must end in `BUILD SUCCESSFUL` |
| `./gradlew test --tests DemoFlowIT` | **The whole demo in one test**: register → login → book → double-book 409 → report damage → the booking moves to a twin asset. A failure names the step, its owner and the class to open |
| `./gradlew test --tests AssetServiceTest` | One test class |
| `./gradlew test --rerun` | Runs every test again (Gradle skips unchanged ones otherwise) |
| `./gradlew pitest` | Mutation testing, on demand (~3 min): breaks the code on purpose and lists what no test caught. Report: `backend/build/reports/pitest/index.html` |
| `cd frontend && npm run lint && npm run build` | Frontend lint + type check + production build |

Reports after `build`: tests in `backend/build/reports/tests/test/index.html`, coverage in `backend/build/reports/jacoco/test/html/index.html`. How to read a red test and what to do at the defense: [`docs/testing/defense-playbook.md`](docs/testing/defense-playbook.md).

**If something doesn't start**

| Problem | Fix |
|---|---|
| `Connection to localhost:5432 refused` | PostgreSQL isn't running: step 1 |
| `password authentication failed for user "campus"` | The user/databases were never created: section 1.1 |
| Flyway error (`checksum mismatch`, `Detected applied migration not resolved locally`) after switching branches | Reset the local database (section 2) |
| `Port 8080 was already in use` | Another backend is still running: stop it (IntelliJ ■ or `Ctrl+C`) |
| The app at :5173 shows errors or "network error" on every page | The backend isn't running, or not on :8080 |

---

## What it is built with

| | |
|---|---|
| Backend | Java 25, Spring Boot 4.1, Gradle (wrapper), Spring Security (JWT), JPA + Flyway |
| Database | PostgreSQL 18: installed on your laptop (+ pgAdmin), a service container in CI, RDS on AWS |
| Frontend | React + Vite + TypeScript (in `frontend/`) |
| Cloud | AWS: one EC2 server (Docker) behind CloudFront, RDS PostgreSQL 18, S3, SES ([ADR-002](docs/adr/002-single-ec2-behind-cloudfront.md)), managed by Matei. **You don't need an AWS account.** |

> **Status (9 Nov 2026, week 6):** on `main`: security (register/login with JWT, `/users/me`, admin-only `/api/v1/admin/**`, admin user endpoints), the **catalog** with admin CRUD, retire, search and **dev seed data**, **reservations** (overlap check, my reservations, cancel, availability, check-out/return, no-show and overdue jobs; also bundle booking, extend, transfer and a 1-minute slot hold), **incidents** (report, triage, my incidents, photo upload), the **auto-reassignment engine**, ArchUnit rules, the Docker image and the S3/SES adapters for AWS. **The whole prototype demo runs on `main`** and is checked by `DemoFlowIT`. Frontend: login, register, catalog + asset details with quick booking, My reservations + booking calendar, My incidents + report form, admin inventory, incident queue, audit log and Reservations desk. **What is left for each of us, until the defense: [`docs/whats-left.md`](docs/whats-left.md)** (M1 is due 15 Nov). New here? Install the tools (section 1), set up the database (section 1.1), then follow the **Quick start** at the top and do your practice PR (section 3).

---

## 1. Install (once)

Everyone uses the **same major versions**. Version mismatches are the #1 cause of "works on my machine".

| Tool | Version | Notes |
|---|---|---|
| **JDK** | **25** | Must be a JDK, not a JRE. Windows/macOS: [Eclipse Temurin 25](https://adoptium.net/). Linux/WSL: `sudo apt install openjdk-25-jdk` |
| **PostgreSQL** | **18** | See section 1.1. pgAdmin 4 comes with the Windows/macOS installer |
| **IntelliJ IDEA** | latest | Free Ultimate with a student license (it also has a built-in Database tool) |
| **Git** | latest | |
| **Node.js** | 24 LTS | Only needed once the frontend exists |

You do **not** install Gradle. Always use the wrapper: `./gradlew` (macOS/Linux/WSL) or `gradlew.bat` (Windows).

**Configure Git with your GitHub email** (otherwise your commits don't count as yours):

```bash
git config --global user.name  "Your Name"
git config --global user.email "the-email-on-your-github-account@example.com"
```

**Windows + WSL users:** clone the repo inside the Linux filesystem (`~/…`), **not** in `/mnt/c/…` (builds are very slow there).

### 1.1 PostgreSQL 18 + pgAdmin

**Install:**

| OS | How |
|---|---|
| Windows / macOS | The EDB installer from <https://www.postgresql.org/download/> (version **18**). Keep the default port **5432**, tick **pgAdmin 4**, and choose a password for the `postgres` superuser (it stays on your laptop; never commit it). |
| Linux / WSL | `sudo apt install postgresql` and check `psql --version` says **18**. If your distro ships an older version, add the official repo first (<https://www.postgresql.org/download/linux/>). Start it with `sudo service postgresql start`. pgAdmin: install it on Windows (or Linux) and connect to `localhost:5432` (WSL forwards it). |

**Create the project user and databases** (once). In pgAdmin: connect as `postgres` → right-click the server → **Query Tool**, then run:

```sql
CREATE ROLE campus LOGIN PASSWORD 'campus';
CREATE DATABASE campus OWNER campus;
CREATE DATABASE campus_test OWNER campus;
```

On Linux/WSL without pgAdmin: `sudo -u postgres psql` and paste the same three lines.

`campus` / `campus` is a **dev-only** login that everyone uses, so the app needs no configuration. The app creates all tables itself (Flyway) on first start. If you ever need different values, set `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` in your IntelliJ run configuration; never commit them.

**Check it works:** in pgAdmin, add a server `localhost:5432` with user `campus` / password `campus`, and open the `campus` database.

---

## 2. Clone and run

```bash
git clone https://github.com/FundamenteleInginerieiSoftware/campus-hub.git
cd campus-hub/backend
./gradlew build          # build + tests (PostgreSQL must be running), should end with BUILD SUCCESSFUL
./gradlew bootRun        # starts the app on http://localhost:8080
```

On Windows (PowerShell / cmd) use `gradlew.bat build` and `gradlew.bat bootRun`.

The first build downloads Gradle and the dependencies and takes a few minutes. Later builds are fast.

**Check that it works:**

| Open | You should see |
|---|---|
| <http://localhost:8080/actuator/health> | `{"status":"UP"}` |
| <http://localhost:8080/swagger-ui.html> | Swagger UI with the API endpoints |
| <http://localhost:8080/api/v1/auth/ping> | a short JSON message: security lets you through without a token |

**Log in (to call protected endpoints):** on first start, the app creates three dev accounts, all with the password `password`: `student1@campus.test`, `student2@campus.test` and `admin@campus.test`. In Swagger, call `POST /api/v1/auth/login` with `{"email": "admin@campus.test", "password": "password"}`, copy `accessToken`, click **Authorize** and paste it. Every call then sends your token. The accounts are only created when the `users` table is empty: if you added users by hand before, reset the database (below).

**Reset the local database:** stop the app, then in pgAdmin's Query Tool (connected as `postgres`):
`DROP DATABASE campus WITH (FORCE); CREATE DATABASE campus OWNER campus;`
Flyway rebuilds all tables on the next start.

### Open in IntelliJ

1. **File → Open →** the `campus-hub` folder (the repo root).
2. If IntelliJ shows "Gradle build scripts found", click **Load**. Otherwise right-click `backend/build.gradle` → **Link Gradle Project**.
3. **File → Project Structure → SDK:** pick your JDK 25. In **Settings → Build Tools → Gradle**, set **Gradle JVM** to the same JDK 25.
4. **Settings → Build, Execution, Deployment → Compiler → Annotation Processors:** tick **Enable annotation processing** (needed for Lombok).
5. If IntelliJ asks to add IDE settings (`.idea/`) to Git, click **Don't Ask Again**.
6. Run the application class with the green ▶ button.

---

## 3. Your first task: practice pull request

Goal: everyone learns the branch → PR → review flow before writing real code.

```bash
git checkout main
git pull
git checkout -b docs/<your-name>-intro
```

1. Create your personal documentation folder: `documentation/<Surname_Name>/README.md`. Write 3–5 lines: your role and what you will work on in the next two weeks.
2. Commit and push:
   ```bash
   git add documentation/
   git commit -m "docs(<your-name>): add personal documentation folder"
   git push -u origin docs/<your-name>-intro
   ```
3. On GitHub, open a **Pull Request** into `main`. GitHub asks the whole team to review; any teammate can approve.
4. After 1 approval and a green `ci` check, merge with **Squash and merge**. The branch is deleted automatically.

---

## 4. Rules (short version)

The full rules are in [CONTRIBUTING.md](CONTRIBUTING.md) and in the team plan (PDFs in the team chat). Where the plan PDFs disagree with this repo (they still mention Maven, Java 21 and H2), **the repo wins**. The most important rules:

- `main` is protected: **no direct pushes**, every change goes through a PR with **1 approval** and a green `ci` check.
- **One branch per task**, from a fresh `main`: `feat/<module>-<desc>`, `fix/…`, `test/…`, `docs/…`, `chore/…`
- **Commit messages** follow Conventional Commits: `feat(catalog): add asset search`, `fix(reservation): reject end before start`
- **Small PRs** (under ~400 changed lines). Sync daily: `git fetch && git rebase origin/main`.
- Before pushing, run `./gradlew spotlessApply build` (formats code, then build + tests).
- **Only Matei edits the build files** (`build.gradle`, `settings.gradle`, `gradle/`) and `application*.yml`. Need a library? Open an issue labeled `dependency`.
- **Never commit secrets** (passwords, keys, `.env`). If it happens, tell Matei immediately.
- **Never edit a Flyway migration that is already on `main`.** Write a new one.
- Stuck for more than **2 hours**? Ask in the group chat.

---

## 5. Project structure

```
campus-hub/
├── backend/                  Spring Boot app (Gradle, Java 25)
│   ├── build.gradle          (Matei only)
│   └── src/main/
│       ├── java/com/campus/  one package per module: user, catalog, reservation, incident, reassignment, ...
│       │                     (each has a package-info.java: purpose, owner, allowed dependencies)
│       └── resources/
│           ├── application.yml          shared settings
│           ├── application-dev.yml      local dev (local PostgreSQL, default profile)
│           ├── application-prod.yml     AWS (everything from env vars)
│           └── db/migration/            Flyway SQL migrations
├── frontend/                 React app (not yet; Stefania sets it up)
├── docs/                     decisions log, API contract, AWS ADRs + diagram, clickable prototype (docs/prototype/)
├── documentation/            one folder per member (personal docs)
├── infra/                    Terraform for AWS (not yet; Matei)
├── .github/                  CODEOWNERS, CI workflow, PR template, issue templates
└── CONTRIBUTING.md           Git, code, database and test rules (read before your first PR)
```

Inside every module the code is split the same way: `api/` (the only package other modules may import), `domain/`, `repository/`, `service/`, `web/`. Details in [CONTRIBUTING.md](CONTRIBUTING.md).

---

## 6. Team

| # | Member | Role | Owns (code) | GitHub |
|---|---|---|---|---|
| 1 | Matei Necula | Cloud, Security & Auth | build files, `application*.yml`, `config/`, `user/` (auth), `messaging/`, all AWS (`infra/`, Dockerfile, deploy), S3/SES adapters; admin reservations desk page | [@matei-necula](https://github.com/matei-necula) |
| 2 | Liviu Nedelcu | Catalog, Inventory & Shared Services | `catalog/`, `common/error`, `common/paging`, `common/audit`, `storage/`, `notification/`; catalog, inventory and audit log pages | [@NliviuN](https://github.com/NliviuN) |
| 3 | Calin Murariu | Reservations, Scheduling & Reassignment | `reservation/`, `reassignment/`, `common/time` (`Clock`), ArchUnit rules; booking and My reservations pages | [@Swiorx](https://github.com/Swiorx) |
| 4 | Stefania | Incidents, Users & Frontend Lead | `incident/`, user admin (`user/admin/`), `common/security` (`CurrentUser`), `frontend/` (lead), CI (`ci.yml`) | [@m1runa-stefan1a](https://github.com/m1runa-stefan1a) |

**Anyone can review anyone's PR**, within 24h: `.github/CODEOWNERS` requests all teammates on every PR, and 1 approval from any of them is enough. The first one free picks it up.

---

## 7. Progress: what each of us has done

What is on `main` and what is still in review, per member. **As of 9 Nov 2026**; update it when PRs merge. **What is still to do: [`docs/whats-left.md`](docs/whats-left.md).**

### Matei Necula: cloud, security & auth

| Done (merged) | PRs |
|---|---|
| Repo restart: README, CONTRIBUTING, AGENTS, CODEOWNERS, PR/issue templates, `ci` workflow, `main` protection | #12, #16, #18, #26, #49 |
| Gradle backend skeleton (Java 25, Spring Boot 4.1, PostgreSQL 18, Flyway, Spotless, JaCoCo, Swagger) | #13 |
| Decisions log + API contract draft | #15 |
| `users` table, `User` entity and repository | #17 |
| Password hashing (BCrypt), JWT signing, `/api/v1/admin/**` admin-only, Swagger "Authorize" | #25 |
| 401/403 answers as ProblemDetail JSON | #44 |
| `@Async` + `@Scheduled` switched on (for Calin's jobs and listeners) | #45 |
| `UserApi` contract + `UserSummary`, implemented by `UserService` | #46, #48 |
| Register/login request and response records | #47 |
| AWS architecture diagram + ADRs 001-005; AWS account with budget alerts | #20 |
| Clickable prototype of the app (fake data) | #41, #42 |
| Error responses keep their `code` for validation errors, bad JSON and unknown paths | #52 |
| Register + login endpoints (JWT with `sub`, `email`, `roles`; 8 h) | #54 |
| Dev test accounts created on startup (2 students, 1 admin) | #55 |
| `archunit-junit5` added for Calin's architecture tests | #62 |
| Security tests probe paths no controller maps | #70 |
| `AGENTS.md`: `CurrentUser` as an injectable bean in the contract | #53 |
| `GET /api/v1/users/me` (the logged-in user's profile) | #82 |
| First admin on AWS from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (`prod` only) | #88 |
| Status updates in README + AGENTS, team plan W4-W6, what's-left list | #50, #56, #57, #81, #83, #93, #106, #136, #144 |
| Docker image: builds the React app and serves it from Spring Boot (one origin, ADR-002), non-root, health check | #114 |
| No more review buddies: anyone can review anyone's PR (CODEOWNERS + ruleset) | #104 |
| Old tokens ignored on the public `/api/v1/auth/**` endpoints | #126 |
| Firewall-rejected URLs answer ProblemDetail JSON | #127 |
| Disabled or deleted users lose access at once (`UserApi.findActiveRole`, checked on every request with a token) | #128 |
| S3 + SES adapters for `prod` (photos in S3, e-mails through SES); missing S3 keys, slow or failing sends handled | #129, #177 |
| `users.created_at` in whole seconds | #135 |
| Register trims spaces around the email; a token with a bad subject gets 401, not 500 | #175, #176 |
| Admin **Reservations desk** page (today's pickups and returns, check-out/return), sorted by start time on the backend | #138, #194 |
| Security pass as tests: IDOR with two students, students rejected on admin endpoints, no stack traces (`SecurityPassIT`) | #139 |
| Mutation testing on demand (`./gradlew pitest`) + tests for the gaps it found in my code | #180, #182 |
| `DemoFlowIT`: the whole demo through the HTTP API; a failure names the step and its owner | #181 |
| Defense playbook (`docs/testing/defense-playbook.md`) + test rules in `AGENTS.md` | #183 |
| Swagger: 401/403 and `ProblemDetail` errors documented on every endpoint | #184 |
| Security chapter of the report + JWT sequence diagram; UML component + deployment diagrams | #185, #186 |
| All security rules in `docs/api-contract.md` §2; SSM arrow fixed in the AWS diagram | #195 |
| README quick start (run, open, test) at the top | #200 |
| Projects feature (#140): design + plan; engine with bilingual search and free units per model (`GET /api/v1/projects`, `/projects/{slug}`) | #232, #236 |
| Personal docs 01-39 + README index of them | #14 + each PR above, #137 |

**Next:** run the M1 flow and tag `v0.1.0`; Projects PRs 3-5 (content, pages, "Reserve all"); Terraform parts 1 + 2, push the image to ECR (W6); EC2 + CloudFront, first AWS deploy (W7). Full list: `docs/whats-left.md` §4.

### Liviu Nedelcu: catalog & shared services

| Done (merged) | PRs |
|---|---|
| Shared exceptions + `GlobalExceptionHandler` (ProblemDetail errors) | #22 |
| `PageResponse<T>` for paginated lists | #22 |
| `AuditService`, `StorageService`, `NotificationService` interfaces + dev versions (logging, local disk) | #22 |
| Personal documentation folder | #21 |
| Catalog: `categories`, `lab_rooms`, `assets` tables + entities, real `CatalogApi` (incl. `lockForBooking`), `docs/modules/catalog.md` | #24 |
| Real `AuditService` writing the `audit_logs` table | #64 |
| Personal docs W2-W4 | #68 |
| Category, lab room and asset CRUD + retire (admin), asset details | #66 (+ #73) |
| Catalog admin actor via `CurrentUser` | #89 |
| Local disk storage with security checks (type, size, path traversal) | #90 |
| Asset search, filters and paging (`GET /api/v1/assets`) | #92 |
| Query-parameter validation errors → 400 `VALIDATION_FAILED` | #110 |
| Large page offsets → 400, audit errors kept apart from duplicate-name errors, error contract aligned | #143 |
| Fewer catalog queries, audit times in whole seconds, constraint checks | #192 |
| **`CatalogSeeder`** (dev): 4 categories, 3 lab rooms, 18 assets, several units of the same model | #193 |
| Frontend: catalog page, asset details page, admin inventory page | #196 |
| `docs/modules/common.md` | #197 |
| Audit log search `GET /api/v1/admin/audit-logs` + audit log page | #217, #221 |
| Swagger error codes, `STALE_VERSION` test, PIT survivors in catalog/common/storage | #220 |
| Catalog/common class + ER diagrams, report chapter | #222 |

**Next:** retire flow with Calin (W7); coverage, final diagrams, demo part (W10–W12). Full list: `docs/whats-left.md` §5.

### Calin Murariu: reservations & reassignment

| Done (merged) | PRs |
|---|---|
| Shared `Clock` bean (Europe/Bucharest) for every "now" in the app | #27 |
| Reservations table, entity and `ReservationApi` | #34 |
| Booking with overlap check and row locking | #35 |
| My reservations, cancel, facade, seeder | #36 |
| Auto-reassignment engine on `AssetDamagedEvent` (same model, then same category, else cancel) | #37 |
| Reservation + reassignment docs | #39 |
| ArchUnit module boundary and coding convention rules | #76 |
| Admin desk: reservation search, check-out, return | #80 |
| Check-out/return windows, scheduled no-show and overdue jobs | #60 |
| Equipment availability endpoint | #78 |
| Frontend: My reservations page + calendar booking view, `/reservations/me/summary` | #87 |
| Fix: rollback test used the real clock and broke once its date passed | #95 |
| `GET /api/v1/admin/reassignments` (paged reassignment log) | #96 |
| Concurrency test: two threads book the same slot, exactly one wins | #97 |
| Boundary tests for every booking rule | #98 |
| Engine hardening: retired assets excluded, deterministic tie-break | #99 |
| "Moved" / "force-cancelled" badges in My reservations | #100 |
| Reservation diagrams, team test plan, Strategy pattern doc | #101 |
| More unit + web slice tests, 3 new ArchUnit rules (shared `Clock`, `@Transactional` placement, no ORDINAL enums) | #102 |
| Review follow-ups: badge check, test cleanup, coverage worded as a target | #109 |
| Timestamps in whole seconds (reservations, reassignment log) | #130 |
| No check-out of an overdue or non-bookable asset | #131 |
| Reassignment: one transaction per booking, one failed move no longer rolls back the rest | #132 |
| My reservations: loader and error reset on every fetch | #133 |
| OVERDUE bookings block the asset until returned | #141 |
| Reassignment: candidate lookup outside the lock transaction, retry with the next substitute; same room first in the preventive cascade | #152, #161 |
| Huge page offsets → 400; per-user booking limit checked under an advisory lock | #153, #173 |
| Admin cancel reason and notification, `sortDir` on the admin search | #172 |
| Sweep/reassignment indexes + admin Swagger docs; Swagger examples and error codes | #162, #191 |
| Frontend fixes: Today button, 14-day slot horizon, campus clock, no cancel after the start | #154, #163, #174 |
| More ArchUnit rules: method-level `@CrossOrigin`/`@Transactional`, Boot 4 imports, admin prefix | #179 |
| Tests for remaining branches and PIT survivors in reservation and reassignment | #178, #201 |
| Test plan with PIT, Strategy pattern doc, booking sequence diagram, report chapter, personal doc 15 | #199, #205, #204, #203, #202 |
| Observer pattern doc | #207 |
| New (not in the original plan): multi-asset bundle booking, extend an active booking, transfer a booking to a classmate, 1-minute slot hold | #212, #213, #214, #219 |
| Frontend: quick-book modal on the catalog and asset details, report-incident button | #226 |
| Extend + transfer in My reservations; hold countdown in the calendar view; tests for the remaining PIT survivors | #224, #225, #227 |
| Time-zone offsets rejected; bundle/transfer/hold security fixes (#228); `ReservationApi.bookBundle` for Projects | #233, #234, #235 |

| Parked (draft, after M1) | PRs |
|---|---|
| Maintenance simulator, waitlist, slot recommender | #58, #59, #61 |

**Next:** no new features until the team agrees which off-plan ones stay; `AssetRetiredEvent` listener if the team picks it (W7); test lead from W10. Full list: `docs/whats-left.md` §6.

### Stefania: incidents, users & frontend lead

| Done (merged) | PRs |
|---|---|
| Personal documentation folder | #19 |
| Redesigned clickable prototype (`docs/prototype/campus-hub-redesign.html`) | #51 |
| `CurrentUser` as an injectable bean | #74 |
| Incident reporting flow, persistence, `AssetDamagedEvent` | #40 |
| Frontend scaffold (Vite + React + TypeScript + Mantine, routing) | #84 |
| API client, auth context, login page | #85 |
| App layout, role-based routes, shared components, conventions (`docs/modules/frontend.md`) | #86 |
| Register page | #107 |
| Incident triage (start repair, reject), `GET /incidents/me`, `GET /incidents/{id}` | #108 |
| Every triage action audited, list indexes, error responses in Swagger | #208 |
| Incident photo upload + view (reporter or admin), orphan files cleaned up | #210 |
| Admin user endpoints: search, details, enable/disable, change role | #206 |
| ADR-002 ✅ in `docs/decisions.md` (Spring serves the React build) | #111 |
| Frontend: keep the token when the backend is down on startup; unique `ErrorAlert` keys, `/admin` index route | #142, #145 |
| Personal docs; `CurrentUser.isAdmin()` Javadoc fix | #198, #215 |
| Frontend: My incidents page + Report a problem form with photo | #216 |
| Frontend: admin incident queue (start repair, resolve, reject, photo view) | #223 |

**Next:** run the M1 flow with the team (W6); incident details page (W7); reassignment log page (W8). Full list: `docs/whats-left.md` §7.

### Everyone

- Still to decide (🟡/⏳ in `docs/decisions.md`): weekly meeting time, entity freeze session, real week dates and milestones.

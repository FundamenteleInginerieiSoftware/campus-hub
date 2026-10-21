# Campus Equipment & Incident Hub

University project for **Fundamentele Ingineriei Software (FIS)**.
Students reserve shared lab equipment by time slot and report broken equipment with a photo.
When an asset is reported damaged, an **auto-reassignment engine** moves its upcoming bookings to a substitute (or cancels them) and notifies the students.

| | |
|---|---|
| Backend | Java 25, Spring Boot 4.1, Gradle (wrapper), Spring Security (JWT), JPA + Flyway |
| Database | PostgreSQL 18: installed on your laptop (+ pgAdmin), a service container in CI, RDS on AWS |
| Frontend | React + Vite + TypeScript (in `frontend/`) |
| Cloud | AWS (ECS Fargate, RDS, S3, CloudFront), managed by Matei. **You don't need an AWS account.** |

> **Status (21 Oct 2026):** the backend project (Gradle, in `backend/`) lands on `main` soon. Until then: install the tools (section 1), set up the database (section 1.1) and do your practice PR (section 3).

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
3. On GitHub, open a **Pull Request** into `main` and request a review from your **review buddy** (see the Team table).
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
├── frontend/                 React app (Stefania sets it up)
├── docs/                     requirements, API contract, diagrams, decisions, meeting notes
├── documentation/            one folder per member (personal docs)
├── infra/                    Terraform for AWS (Matei)
├── .github/                  CODEOWNERS, CI workflow, PR template, issue templates
└── CONTRIBUTING.md           Git, code, database and test rules (read before your first PR)
```

Inside every module the code is split the same way: `api/` (the only package other modules may import), `domain/`, `repository/`, `service/`, `web/`. Details in [CONTRIBUTING.md](CONTRIBUTING.md).

---

## 6. Team

| # | Member | Role | Owns (code) | GitHub | Review buddy |
|---|---|---|---|---|---|
| 1 | Matei Necula | Cloud, Security & Auth | build files, `application*.yml`, `config/`, `user/` (auth), `messaging/`, all AWS (`infra/`, Dockerfile, deploy), S3/SES adapters; admin reservations desk page | [@matei-necula](https://github.com/matei-necula) | Liviu |
| 2 | Liviu Nedelcu | Catalog, Inventory & Shared Services | `catalog/`, `common/error`, `common/paging`, `common/audit`, `storage/`, `notification/`; catalog, inventory and audit log pages | [@NliviuN](https://github.com/NliviuN) | Matei |
| 3 | Calin Murariu | Reservations, Scheduling & Reassignment | `reservation/`, `reassignment/`, `common/time` (`Clock`), ArchUnit rules; booking and My reservations pages | [@Swiorx](https://github.com/Swiorx) | Stefania |
| 4 | Stefania | Incidents, Users & Frontend Lead | `incident/`, user admin (`user/admin/`), `common/security` (`CurrentUser`), `frontend/` (lead), CI (`ci.yml`) | [@m1runa-stefan1a](https://github.com/m1runa-stefan1a) | Calin |

Your **review buddy** reviews your PRs (and you review theirs) within 24h. GitHub requests the right reviewer automatically through `.github/CODEOWNERS`.

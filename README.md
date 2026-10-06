# Campus Equipment & Incident Hub

University project for **Fundamentele Ingineriei Software (FIS)**.
Students reserve shared lab equipment by time slot and report broken equipment with a photo.
When an asset is reported damaged, an **auto-reassignment engine** moves its upcoming bookings to a substitute (or cancels them) and notifies the students.

| | |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Maven (wrapper), Spring Security (JWT), JPA + Flyway |
| Database | H2 file DB locally (zero install), PostgreSQL 17 on AWS |
| Frontend | React + Vite + TypeScript (coming soon, in `frontend/`) |
| Cloud | AWS (ECS Fargate, RDS, S3, SQS, CloudFront), managed by Matei. **You don't need an AWS account.** |

---

## 1. Install (once)

Everyone uses the **same major versions**. Version mismatches are the #1 cause of "works on my machine".

| Tool | Version | Notes |
|---|---|---|
| **JDK** | **21** | Must be a JDK, not a JRE. Windows/macOS: [Eclipse Temurin 21](https://adoptium.net/). Linux/WSL: `sudo apt install openjdk-21-jdk` |
| **IntelliJ IDEA** | latest | Free Ultimate with a student license |
| **Git** | latest | |
| **Node.js** | 24 LTS | Only needed once the frontend exists |

You do **not** install Maven. Always use the wrapper: `./mvnw` (macOS/Linux/WSL) or `mvnw.cmd` (Windows).

**Configure Git with your GitHub email** (otherwise your commits don't count as yours):

```bash
git config --global user.name  "Your Name"
git config --global user.email "the-email-on-your-github-account@example.com"
```

**Windows + WSL users:** clone the repo inside the Linux filesystem (`~/…`), **not** in `/mnt/c/…` (builds are very slow there).

---

## 2. Clone and run

```bash
git clone https://github.com/FundamenteleInginerieiSoftware/campus-hub.git
cd campus-hub/backend
./mvnw verify            # build + tests, should end with BUILD SUCCESS
./mvnw spring-boot:run   # starts the app on http://localhost:8080
```

On Windows (PowerShell / cmd) use `mvnw.cmd verify` and `mvnw.cmd spring-boot:run`.

The first build downloads dependencies and takes a few minutes. Later builds are fast.

**Check that it works:** open <http://localhost:8080/actuator/health>.
For now, Spring Security protects everything with a temporary login:

- username: `user`
- password: printed in the console at startup (`Using generated security password: …`)

You should see `{"status":"UP"}`. Login with real accounts, Swagger UI and the H2 console are coming in week 3.

**Reset the local database:** stop the app, delete `backend/data/`, start again.

### Open in IntelliJ

1. **File → Open →** the `campus-hub` folder (the repo root, not `backend/`).
2. If IntelliJ shows "Maven build scripts found", click **Load**. Otherwise right-click `backend/pom.xml` → **Add as Maven Project**.
3. **File → Project Structure → SDK:** pick your JDK 21.
4. **Settings → Build, Execution, Deployment → Compiler → Annotation Processors:** tick **Enable annotation processing** (needed for Lombok).
5. If IntelliJ asks to add IDE settings (`.idea/`) to Git, click **Don't Ask Again**.
6. Run `CampusHubApplication` with the green ▶ button.

---

## 3. Your first task: practice pull request

Goal: everyone learns the branch → PR → review flow before writing real code.

```bash
git checkout main
git pull
git checkout -b docs/readme-add-<your-name>
```

1. Add yourself to the **Team** table at the bottom of this README.
2. Commit and push:
   ```bash
   git add README.md
   git commit -m "docs(readme): add <your name> to team"
   git push -u origin docs/readme-add-<your-name>
   ```
3. On GitHub, open a **Pull Request** into `main` and request a review from a teammate.
4. After 1 approval, merge with **Squash and merge**. The branch is deleted automatically.

If two people edit the same line at the same time, you get a **merge conflict**. That's expected, and it's good practice. Resolve it together.

---

## 4. Rules (short version)

The full rules are in the team handbook. The most important ones:

- `main` is protected: **no direct pushes**, every change goes through a PR with **1 approval**.
- **One branch per task**, from a fresh `main`: `feat/<module>-<desc>`, `fix/…`, `test/…`, `docs/…`, `chore/…`
- **Commit messages** follow Conventional Commits: `feat(catalog): add asset search`, `fix(reservation): reject end before start`
- **Small PRs** (under ~400 changed lines). Sync daily: `git fetch && git rebase origin/main`.
- Before pushing, run `./mvnw spotless:apply` (formats code) and `./mvnw verify` (build + tests).
- **Only Matei edits `pom.xml`** and `application*.yml`. Need a library? Open an issue labeled `dependency`.
- **Never commit secrets** (passwords, keys, `.env`). If it happens, tell Matei immediately.
- **Never edit a Flyway migration that is already on `main`.** Write a new one.
- Stuck for more than **2 hours**? Ask in the group chat.

---

## 5. Project structure

```
campus-hub/
├── backend/                  Spring Boot app (Maven)
│   ├── pom.xml               (Matei only)
│   └── src/main/
│       ├── java/com/campus/  one package per module: user, catalog, reservation, incident, ...
│       └── resources/
│           ├── application.yml          shared settings
│           ├── application-dev.yml      local dev (H2, default profile)
│           ├── application-prod.yml     AWS (everything from env vars)
│           └── db/migration/            Flyway SQL migrations
├── frontend/                 React app (coming soon)
├── docs/                     requirements, diagrams, decisions, meeting notes
└── infra/                    Terraform for AWS (Matei)
```

---

## 6. Team

| Member | Role | GitHub |
|---|---|---|
| Matei | Cloud, Security, User & Platform | [@matei-necula](https://github.com/matei-necula) |
| | Asset Catalog & Inventory | |
| | Reservations & Scheduling | |
| | Incidents, Reassignment & Frontend | |

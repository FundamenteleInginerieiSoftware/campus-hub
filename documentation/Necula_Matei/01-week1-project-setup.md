# Week 1: Project setup (Necula Matei)

**Role:** Member 1, Cloud, Security, User & Platform
**Date:** 2026-10-06
**Repository:** https://github.com/FundamenteleInginerieiSoftware/campus-hub
**See also:** `02-files-explained.md` (what each file does)

---

## Part 1: In simple terms

I built the **starting point** of our project, so the whole team can begin working from the same base.

1. **Prepared my computer.** I installed the Java 21 development kit, the tool that turns Java code into a running program, and set up IntelliJ, the editor we write code in.
2. **Chose the Java version.** We stay on Java 21. It is a long-term-support version, it works with all our tools, and it is the version the team plan assumes. Java 27 is too new: it is supported for only 6 months, and several of our tools don't fully support it yet.
3. **Created the empty application.** It is a Spring Boot project, the framework we use for the server. It starts, connects to a small built-in database, and passes its first automatic test.
4. **Added settings for two environments:**
   - **"dev"** runs on our laptops with zero setup.
   - **"prod"** will run on AWS (the cloud). It takes all passwords from the server, so no secret is ever stored in our code.
5. **Added quality tools:**
   - one that formats everyone's code the same way, which avoids pointless conflicts;
   - one that measures how much code our tests cover;
   - one that will generate API documentation (Swagger).
6. **Wrote a README for my teammates.** It explains what to install, how to run the project, and their first practice task (a small pull request).
7. **Published the project on GitHub** in our organization, as a public repository.
8. **Protected the main branch.** Nobody can change the main code directly. Every change must go through a **pull request** that another teammate approves. This keeps the main version always working.

**Result:** the team can now download the project, run it, and start practicing the Git workflow.

---

## Part 2: Technical details

### Environment
- **JDK:** OpenJDK 21 on WSL2 (Kali). The `openjdk-21-jre` already installed had no `javac`, so I installed `openjdk-21-jdk` and set `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- **IntelliJ:** project opened at the repo root; annotation processing enabled (Lombok); `.idea/` kept out of Git.
- **Why not Java 25/27:** Spring Boot 4.1 officially supports Java 17–25. JDK 27 is a non-LTS release (support ends March 2027), and Lombok, JaCoCo and Mockito need time to catch up with new JDKs.

### Project skeleton (`backend/`)
- Generated with Spring Initializr in IntelliJ: **Spring Boot 4.1.1**, Java 21, Maven wrapper, `com.campus:campus-hub`, YAML config.
- **Starters:** `webmvc`, `data-jpa`, `validation`, `security`, `security-oauth2-resource-server` (JWT), `flyway` + `flyway-database-postgresql`, `actuator`, H2 + `h2console`, PostgreSQL driver, Lombok, DevTools, plus the matching Boot 4 test starters.
- **Added to `pom.xml`:**

  | Item | Version | Purpose |
  |---|---|---|
  | `springdoc-openapi-starter-webmvc-ui` | 3.1.1 | Swagger UI |
  | `spotless-maven-plugin` + palantir-java-format | 3.10.3 / 2.102.0 | Formatting; `check` runs in `verify` |
  | `jacoco-maven-plugin` | 0.8.15 | Coverage report at `target/site/jacoco/` |

- Main class renamed to `CampusHubApplication`. `./mvnw verify` → **BUILD SUCCESS**.

### Configuration (Spring profiles)

| File | Content |
|---|---|
| `application.yml` | `profiles.default: dev`, `open-in-view: false`, `ddl-auto: validate` (Flyway owns the schema), 5 MB upload limit, RFC 9457 ProblemDetail errors, actuator exposes only `health,info`, `app.time-zone: Europe/Bucharest`, JWT lifetime 8h |
| `application-dev.yml` | H2 file DB in PostgreSQL mode (`backend/data/`), H2 console, SQL logging, fake JWT secret (≥ 32 bytes for HS256), local upload folder |
| `application-prod.yml` | Every value from env vars (`DB_URL`, `JWT_SECRET`, `PHOTO_BUCKET`, `SQS_QUEUE_URL`…); the admin bootstrap variables default to empty |
| `.env.example` | Names of the prod variables, with no values |

`profiles.default` (not `active`) is used so that AWS can override it with `SPRING_PROFILES_ACTIVE=prod`.

### Repository
- **Layout:** repo root with `backend/` (later `frontend/`, `docs/`, `infra/`).
- **Root files:**
  - `.gitignore`: build output, `data/`, `uploads/`, IDE files, `.env`, Terraform state.
  - `.gitattributes`: LF line endings everywhere, CRLF only for `.cmd`/`.bat`.
  - `.editorconfig`.
- `mvnw` committed as executable (`100755`), so CI and Linux/macOS clones can run it.
- **First commit:** `4f04b78 chore: initial project skeleton`.
- **Public repo in the org.** The org is on GitHub Free, and branch protection on private repos needs a paid plan (my personal Pro plan doesn't apply to organizations).

### GitHub protections (applied with `gh api`)
- **Merging:** squash merge only; the squash commit title is the PR title (Conventional Commits); branches are deleted automatically after merge.
- **Security:** secret scanning + push protection enabled; Dependabot alerts enabled.
- **Ruleset `protect-main`** on the default branch:
  - no deletion, no force-push;
  - pull request required with **1 approval**;
  - stale approvals dismissed on new pushes;
  - review comments must be resolved before merging;
  - squash merge only.
- **Temporary bypass for week 1:** admins can merge their own PRs without approval ("for pull requests only"), so the platform setup isn't blocked. Direct pushes are still blocked.

### Pending
1. **Step 10:** a `GET /api/v1/auth/ping` endpoint, a temporary `SecurityConfig`, and the first Flyway migration.
2. **Project infrastructure:** `ci.yml` (with a PostgreSQL 17 service), `CODEOWNERS`, PR and issue templates, and the module package structure.
3. **Ruleset updates:**
   - add the required status check `ci` once CI has run;
   - enable code-owner review once `CODEOWNERS` exists;
   - remove the week-1 bypass at the end of W1.
4. **Teammates:** invite them with Write access; everyone runs the project and opens a practice PR.

# Project setup, round 2 (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-21
**Repository:** https://github.com/FundamenteleInginerieiSoftware/campus-hub
**See also:** `02-files-explained.md` (what each file does)

---

## Part 1: In simple terms

On 8 October we emptied the repository (#10) to start clean. This week I rebuilt the starting point with the tools the team finally agreed on.

1. **Fixed who does what.** We split the work again so nobody is overloaded. I keep security, login and the cloud (AWS), plus one admin page. Some shared pieces I used to own now belong to my teammates.
2. **Picked the tools.** We switched to **Gradle** (the build tool) and **Java 25**, because Liviu had already started with them.
3. **Picked the database.** Everyone installs a real **PostgreSQL 18** database on their own laptop and looks at the data with **pgAdmin**. It is the same database we will use in the cloud, so "works on my laptop, breaks on AWS" surprises go away.
4. **Reviewed Liviu's first code.** The ideas were good, but it was built as a separate project with different settings. He will move it into the shared project.
5. **Put the team guides back on GitHub** (README, contribution rules, instructions for everyone's AI assistant, automatic reviewer assignment, the automatic test run).
6. **Rebuilt the server project** in IntelliJ, step by step, with the new tools.
7. **Checked that it works:** it builds, all tests pass, it starts, and GitHub's automatic check is green.

**Result:** the team has one shared, working project again (pull request #13). Teammates can now build their parts on it.

---

## Part 2: Technical details

### Role split (v2)

| Who | Owns |
|---|---|
| Matei | `user/` auth (register, login, JWT), `SecurityConfig` + `config/`, build files, `application*.yml`, all AWS (Terraform, Dockerfile, ECR/ECS, `deploy.yml`, S3/SES adapters, secrets, CloudWatch), SQS (stretch), admin reservations desk page |
| Liviu | `catalog/`, `common/error` + `GlobalExceptionHandler`, `common/paging`, `common/audit`, `storage/` + `notification/` interfaces with local implementations; catalog/inventory/audit pages |
| Calin | `reservation/` + overdue/no-show jobs, `reassignment/` engine + strategies, `Clock`, ArchUnit rules; booking + My reservations pages |
| Stefania | `incident/`, user admin endpoints + `CurrentUser`, frontend lead, CI (`ci.yml`) |

### Decisions

| Decision | Why |
|---|---|
| **Gradle + Java 25**, project in `backend/`, package `com.campus` | Follows Liviu's #11. Spring Boot 4.1 supports Java 17–25, Gradle 9.8 runs on 25, and the foojay plugin downloads JDK 25 if a teammate doesn't have it |
| **Local PostgreSQL 18 + pgAdmin** | Considered: H2, Docker Compose + Testcontainers, zonky embedded Postgres, local install, hosted DB per person, RDS per developer, one shared DB. Local install is the easiest first day for beginners (no Docker), it is real Postgres, RDS in eu-central-1 offers 18.x, and Kali's apt ships 18 |
| **SQS = stretch goal** | Behind `app.messaging.sqs.enabled` (default `false`): the local event listener does the work until the SQS bridge exists |

### Review of Liviu's PR #11

| Finding | Status |
|---|---|
| Gradle at the repo root, Java 25, package `com.campus.incident_hub` | Moves into `backend/`, package `com.campus` |
| `ddl-auto: update`, local DB password committed in `application.properties` | Replaced by Flyway + `campus/campus` dev login |
| Entities in controllers, hard delete of assets | Must use DTO records and retire instead of delete |
| `AssetSummary` missing `categoryId` / `labRoomId` | Needed by Calin's substitute strategies |
| Error map instead of `ProblemDetail` | Liviu now owns the handler; redo it as RFC 9457 |
| Good: domain model, state rules in `Asset`, `@Version`, pessimistic `lockForBooking` | Kept |

Outcome: don't merge #11; once #13 is on `main`, Liviu moves the code into `backend/src/main/java/com/campus/catalog/`.

### PR #12: repo guides

Restored and updated for v2: `AGENTS.md`, `CLAUDE.md`, `README.md` (JDK 25 + PostgreSQL 18 setup per OS), `CONTRIBUTING.md`, `.github/CODEOWNERS`, PR/issue templates, `.github/workflows/ci.yml`, `.gitignore`, `.gitattributes`, `.editorconfig`. The `ci` workflow skips backend/frontend steps until those projects exist, so docs-only PRs still pass the required check.

### Local machine

- Deleted the old local folder and re-cloned the repo into `~/campus`.
- PostgreSQL 18 in WSL: `sudo service postgresql start` (after every reboot), then the `campus` role and the `campus` + `campus_test` databases.

### PR #13: Gradle skeleton

Generated with IntelliJ's Spring Boot wizard: Name `backend`, Gradle - Groovy, Java 25, Spring Boot 4.1.1, YAML config, the same starters as before minus H2.

| Added to `build.gradle` | Purpose |
|---|---|
| `jacoco` + `jacocoTestReport` (HTML + XML) | Coverage report after every test run |
| Spotless 8.10.4 + palantir-java-format 2.102.0 | Formatting; `build` fails if code isn't formatted |
| springdoc-openapi 3.1.1 | Swagger UI (3.x is the Boot 4 line) |
| `systemProperty 'spring.profiles.active', 'test'` | Tests use the `test` profile |

| File | Content |
|---|---|
| `settings.gradle` | foojay toolchain resolver, `rootProject.name = 'campus-hub'` |
| `application.yml` | Shared settings, `ddl-auto: validate`, `app.messaging.sqs.enabled: false` |
| `application-dev.yml` | Local PostgreSQL `campus/campus`, overridable with `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` |
| `application-prod.yml` | Everything from environment variables |
| `application-test.yml` | `campus_test` database, `@DataJpaTest` uses it (no embedded DB) |
| `V2026_10_21_1500__platform_init.sql` | First Flyway migration |

Code: `SecurityConfig` (my week-1 version without the H2 console chain), `GET /api/v1/auth/ping`, a `package-info.java` per module with the v2 owners, 3 test classes.

Fixes after the wizard: `BackendApplication` → `CampusHubApplication` (the wizard named it after the folder), `application.yaml` → `.yml`, and `gradlew` made executable (`100755`), because the wizard wrote it from Windows without the execute bit.

### Verification

| Check | Result |
|---|---|
| `./gradlew build` on local PostgreSQL 18 | BUILD SUCCESSFUL, 6/6 tests, Spotless OK, JaCoCo report |
| Flyway | `flyway_schema_history` has the first migration |
| `bootRun` (dev profile) | health `UP`, ping `pong`, Swagger 200, `/api/v1/assets` 401 |
| CI on #13 | green in 1m31s (Gradle build on a `postgres:18` service container) |

### Next

1. Review and merge #13; Liviu moves his catalog code from #11.
2. Login/register with JWT, plus 401/403 responses as `ProblemDetail` JSON.
3. AWS account hygiene; upgrade Terraform to ≥ 1.10.

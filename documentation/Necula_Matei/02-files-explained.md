# Week 1: What each file does (Necula Matei)

Every file added to the repository so far, explained twice: first in simple terms, then technically.
For the story of how the setup was done, see `01-week1-project-setup.md`.

```
campus-hub/
├── README.md
├── .gitignore  .gitattributes  .editorconfig  .env.example
├── documentation/Necula_Matei/
└── backend/
    ├── pom.xml
    ├── mvnw  mvnw.cmd  .mvn/wrapper/maven-wrapper.properties
    └── src/
        ├── main/java/com/campus/CampusHubApplication.java
        ├── main/resources/application.yml  application-dev.yml  application-prod.yml
        ├── main/resources/db/migration/   (empty for now)
        └── test/java/com/campus/CampusHubApplicationTests.java
```

---

## Part 1: In simple terms

### Repository root (whole project)
| File | What it does |
|---|---|
| `README.md` | The welcome page on GitHub: what the project is, what to install, how to run it, and the first practice task. |
| `.gitignore` | A list of files Git must **never** upload: temporary build files, the local database, editor settings, passwords. |
| `.gitattributes` | Makes sure line endings are the same on Windows, Mac and Linux, so files don't look "completely changed" for no reason. |
| `.editorconfig` | Tells every editor the same formatting basics: spaces instead of tabs, how many spaces, which file encoding. |
| `.env.example` | A checklist of the secret settings the cloud server needs (database password, etc.). It contains only the **names**, never the real values. |
| `documentation/Necula_Matei/` | My personal work log for the project report. |

### Backend (the server application)
| File | What it does |
|---|---|
| `pom.xml` | The project's "shopping list": which libraries we use, in which versions, and how the project is built. |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/…` | A small launcher for the build tool. Nobody has to install Maven: the launcher downloads the right version automatically. `mvnw` is for Linux/Mac, `mvnw.cmd` is for Windows. |
| `CampusHubApplication.java` | The "start button" of the server. Running it starts the whole application. |
| `application.yml` | Settings shared by every environment (the time zone, the max photo size, how long a login lasts…). |
| `application-dev.yml` | Settings for our laptops: a small built-in database stored in a file, plus a fake password. No setup needed. |
| `application-prod.yml` | Settings for the cloud (AWS). It holds no real values: they are read from the server at startup. |
| `db/migration/` | Will hold the scripts that create the database tables, one script per change. Empty for now. |
| `CampusHubApplicationTests.java` | The first automatic test. It checks that the application can start at all. |

### Not uploaded to GitHub (local only)
| Folder | What it is |
|---|---|
| `backend/target/` | Build output (compiled code, test reports). Recreated on every build. |
| `backend/data/` | The local database file. Delete it to start with an empty database. |
| `.idea/` | IntelliJ's personal settings. Different on every computer. |

---

## Part 2: Technical details

### Repository root
| File | Technical role |
|---|---|
| `README.md` | Onboarding: prerequisites (JDK 21, IntelliJ, git identity), `./mvnw verify` / `spring-boot:run`, IntelliJ import steps, practice-PR workflow, short Git rules, module layout, team table. |
| `.gitignore` | Ignores `backend/target/`, `backend/data/`, `backend/uploads/`, `*.mv.db`/`*.trace.db` (H2), `maven-wrapper.jar`, `frontend/node_modules/` and `dist/`, `.idea/`, `*.iml`, `.vscode/`, `.env`, `*.env.local`, and Terraform state (`*.tfstate*`, `*.tfvars`, `.terraform/`). |
| `.gitattributes` | `* text=auto eol=lf` normalizes line endings to LF in the repo and in checkouts; `*.cmd`/`*.bat` stay CRLF (required by Windows); images and jars are `binary` (no diff, no line-ending conversion). Prevents `bad interpreter` errors on `mvnw` and whole-file diffs. |
| `.editorconfig` | `root = true`; UTF-8, LF, final newline, trim trailing whitespace; 4-space indent by default, 2 spaces for yml/json/js/ts/css/html; CRLF for `.cmd`/`.bat`. Read natively by IntelliJ and VS Code. |
| `.env.example` | Contract for the `prod` profile: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `PHOTO_BUCKET`, `SQS_QUEUE_URL`, `AWS_REGION`, `MAIL_FROM`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`. In AWS the values come from SSM Parameter Store, injected into the ECS task. Spring does not read `.env` files itself. |

### Backend build
| File | Technical role |
|---|---|
| `pom.xml` | Parent `spring-boot-starter-parent:4.1.1` (manages dependency versions); `java.version=21`. **Starters:** `webmvc`, `data-jpa`, `validation`, `security`, `security-oauth2-resource-server` (JWT validation with Nimbus), `flyway` + `flyway-database-postgresql`, `actuator`; runtime `h2`, `h2console`, `postgresql`, `devtools`; `lombok` (also configured as an annotation processor in `maven-compiler-plugin`); Boot 4 per-module test starters (including `security-test`). **Added:** `springdoc-openapi-starter-webmvc-ui` 3.1.1 (OpenAPI + Swagger UI), `spotless-maven-plugin` 3.10.3 with palantir-java-format 2.102.0 and `removeUnusedImports` (the `check` goal is bound to `verify`), `jacoco-maven-plugin` 0.8.15 (`prepare-agent` + `report` in `verify`). Only Matei edits this file. |
| `mvnw` / `mvnw.cmd` | Maven Wrapper 3.3.4 scripts (`distributionType=only-script`). They download Maven 3.9.16 into `~/.m2/wrapper` on first use. `mvnw` is committed with mode `100755` so CI can execute it. |
| `.mvn/wrapper/maven-wrapper.properties` | Pins the Maven distribution URL and version, so every machine and CI builds with the same Maven. |

### Backend source
| File | Technical role |
|---|---|
| `CampusHubApplication.java` | `@SpringBootApplication` in the root package `com.campus`. It enables component scanning for `com.campus.**` (all future modules: `user`, `catalog`, `reservation`, …) and auto-configuration. `main` calls `SpringApplication.run`. |
| `application.yml` | `spring.profiles.default: dev` (overridable by `SPRING_PROFILES_ACTIVE=prod`); `jpa.open-in-view: false` (no lazy loading in controllers; map to DTOs inside `@Transactional` services); `hibernate.ddl-auto: validate` (Flyway owns the schema; Hibernate fails at startup if entities don't match it); multipart 5 MB/6 MB; `mvc.problemdetails.enabled` (RFC 9457 error bodies); actuator exposes `health,info` only; custom `app.time-zone: Europe/Bucharest` (for the shared `Clock` bean) and `app.jwt.expiration: 8h`. |
| `application-dev.yml` | Datasource `jdbc:h2:file:./data/campusdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` (persistent file DB that behaves close to PostgreSQL); `h2.console.enabled`; `jpa.show-sql`; dev-only HS256 secret (≥ 32 bytes); `app.storage.local-dir: ./uploads`. |
| `application-prod.yml` | `${ENV_VAR}` placeholders for the datasource, JWT secret, S3 bucket, SQS queue URL, AWS region and SES sender. Placeholders without defaults fail fast at startup if a variable is missing. `ADMIN_EMAIL`/`ADMIN_PASSWORD` use `${…:}` (empty default), so the admin bootstrap is optional. |
| `db/migration/` | Flyway location (`classpath:db/migration`). Files will be named `V<YYYY_MM_DD_HHMM>__<module>_<desc>.sql` to avoid version collisions between team members. Merged migrations are never edited. Empty folders aren't tracked by Git, so it appears on GitHub with the first migration. |
| `CampusHubApplicationTests.java` | `@SpringBootTest` `contextLoads()`: boots the full context (dev profile, H2, Flyway, security, JPA). A smoke test that catches broken configuration or bean wiring. Its coverage is recorded by JaCoCo. |

### Generated / ignored
| Path | Technical role |
|---|---|
| `backend/target/` | Maven output: compiled classes, Surefire reports, `jacoco.exec`, HTML coverage report in `target/site/jacoco/`, the runnable jar. |
| `backend/data/` | H2 database files (`campusdb.mv.db`). Deleting the folder resets the DB; Flyway rebuilds the schema on the next start. |
| `.idea/` | IntelliJ project metadata (JDK path, run configs). Machine-specific, so it isn't shared. |

# What each file does (Necula Matei)

Every file in the repository after #12, plus the backend from PR #13, the users table (`03-user-entity.md`), the login foundations (`04-password-jwt-roles.md`, `05-security-error-json.md`, `06-async-scheduling.md`, `07-user-api.md`, `08-auth-request-records.md`), the prototype mockup (`10-prototype-mockup.md`) the user lookup (`11-user-service.md`) the error-code fix (`12-error-codes-fix.md`) register/login (`13-auth-register-login.md`) the dev accounts (`14-user-seeder.md`), the security test probe paths (`16-security-probe-paths.md`), "who am I?" (`17-users-me.md`) the first admin on AWS (`18-prod-admin-bootstrap.md`) the Docker image (`19-dockerfile.md`), the login fix for old tokens (`20-auth-ignores-old-token.md`), the firewall error format (`21-firewall-error-json.md`), the account check on every request (`22-token-account-check.md`), the S3/SES adapters (`23-s3-ses-adapters.md`), the whole-second `createdAt` fix (`24-user-created-at-seconds.md`), the security pass tests (`25-security-pass-tests.md`), the admin Reservations desk page (`26-reservations-desk-page.md`) and mutation testing (`33-mutation-testing.md`), explained twice: first in simple terms, then technically.
For the story of how and why it was set up, see `01-project-setup-v2.md`.

```
campus-hub/
├── README.md  CONTRIBUTING.md  AGENTS.md  CLAUDE.md
├── .gitignore  .gitattributes  .editorconfig
├── Dockerfile  .dockerignore                             (19-dockerfile.md)
├── .github/
│   ├── CODEOWNERS  pull_request_template.md
│   ├── ISSUE_TEMPLATE/  bug.md  task.md  user-story.md  config.yml
│   └── workflows/ci.yml
├── docs/  decisions.md  api-contract.md  plan-w4-w6.md  whats-left.md
│   ├── adr/  001-local-first … 005-aws-account-free-plan        (AWS ADRs)
│   ├── diagrams/  aws-architecture.drawio  aws-architecture.png
│   └── prototype/  campus-hub-prototype.html                (10-prototype-mockup.md)
├── documentation/Necula_Matei/  README.md  NN-topic.md   (my notes)
├── frontend/src/  pages/ReservationsDeskPage.tsx  api/reservationDesk.ts   (26-reservations-desk-page.md)
└── backend/                                              (PR #13)
    ├── build.gradle  settings.gradle
    ├── gradlew  gradlew.bat  gradle/wrapper/
    └── src/
        ├── main/java/com/campus/  CampusHubApplication.java, config/SecurityConfig.java,
        │                          config/OpenApiConfig.java, config/SecurityErrorHandler.java,
        │                          config/AsyncConfig.java, config/SpaConfig.java, config/AwsConfig.java,
        │                          storage/service/S3StorageService.java,
        │                          notification/service/SesNotificationService.java,
        │                          user/web/AuthController.java, <module>/package-info.java,
        │                          user/api/Role.java, user/domain/User.java,
        │                          user/repository/UserRepository.java, user/service/UserService.java,
        │                          user/service/AuthService.java, user/service/UserSeeder.java,
        │                          user/service/AdminBootstrap.java,
        │                          user/api/UserApi.java, user/api/UserSummary.java,
        │                          user/web/{Register,Login}Request.java, {User,Login}Response.java,
        │                          user/web/UserController.java
        ├── main/resources/  application.yml  application-dev.yml  application-prod.yml
        │                    db/migration/V2026_10_21_1500__platform_init.sql
        │                    db/migration/V2026_10_21_1528__user_create_users.sql
        ├── test/java/com/campus/  CampusHubApplicationTests, SecurityConfigTest, AuthControllerTest,
        │                          UserRepositoryTest, AsyncConfigTest, AuthRequestsTest, UserServiceTest,
        │                          ErrorJsonTest, AuthServiceTest, AuthFlowIT,
Every file in the repository after #12, plus the backend from PR #13, the users table (`03-user-entity.md`), the login foundations (`04-password-jwt-roles.md`, `05-security-error-json.md`, `06-async-scheduling.md`, `07-user-api.md`, `08-auth-request-records.md`), the prototype mockup (`10-prototype-mockup.md`) the user lookup (`11-user-service.md`) the error-code fix (`12-error-codes-fix.md`) register/login (`13-auth-register-login.md`) the dev accounts (`14-user-seeder.md`), the security test probe paths (`16-security-probe-paths.md`), "who am I?" (`17-users-me.md`) the first admin on AWS (`18-prod-admin-bootstrap.md`) the Docker image (`19-dockerfile.md`), the login fix for old tokens (`20-auth-ignores-old-token.md`), the firewall error format (`21-firewall-error-json.md`), the account check on every request (`22-token-account-check.md`), the S3/SES adapters (`23-s3-ses-adapters.md`), the whole-second `createdAt` fix (`24-user-created-at-seconds.md`), the security pass tests (`25-security-pass-tests.md`), the admin Reservations desk page (`26-reservations-desk-page.md`) and mutation testing (`33-mutation-testing.md`), explained twice: first in simple terms, then technically.
        └── test/resources/application-test.yml  static/index.html
```

---

## Part 1: In simple terms

### Repository root
| File | What it does |
|---|---|
| `README.md` | The welcome page: what the project is, what to install (Java 25, PostgreSQL 18 + pgAdmin), how to create the database, how to run the app, and the first practice task. |
| `CONTRIBUTING.md` | The team rules in short: how to use Git, who owns which folder, database rules, code and test rules. |
| `AGENTS.md` | Instructions for the AI assistants each of us uses, so they follow the same rules: who owns what, what never to do, what to wait for. |
| `CLAUDE.md` | One line that tells Claude Code to read `AGENTS.md`. |
| `.gitignore` | Files Git must never upload: build output, editor settings, passwords. |
| `.gitattributes` | Same line endings on Windows, Mac and Linux, so files don't look "completely changed". |
| `.editorconfig` | Same formatting basics in every editor (spaces, indentation, encoding). |
| `Dockerfile` | The recipe for the box the app runs in on AWS: it builds the website, puts it inside the backend, and keeps only what is needed to run (`19-dockerfile.md`). |
| `.dockerignore` | Files the recipe never copies: Git history, downloaded libraries, build output, passwords. |

### `.github/` (GitHub settings stored as files)
| File | What it does |
|---|---|
| `CODEOWNERS` | Says who GitHub asks to review. Since 4 Nov: the whole team, on every PR. |
| `pull_request_template.md` | The form every pull request starts with: what, why, how to test, a checklist. |
| `ISSUE_TEMPLATE/` | Forms for new issues: bug, task, user story. |
| `workflows/ci.yml` | The robot that builds and tests every pull request. It must be green before merging. |

### Backend: build (PR #13)
| File | What it does |
|---|---|
| `build.gradle` | The project's "shopping list": libraries, Java version, and the formatting and coverage tools. |
| `settings.gradle` | The project name, plus a helper that downloads Java 25 if a laptop doesn't have it. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/` | A small launcher for the build tool. Nobody installs Gradle: it downloads the right version itself. |

### Backend: settings (PR #13)
| File | What it does |
|---|---|
| `application.yml` | Settings for every environment: time zone, upload limit, login lifetime, background threads. |
| `application-dev.yml` | Laptop settings: our local PostgreSQL database and a fake secret. |
| `application-prod.yml` | Cloud settings: no real values, everything is read from AWS at startup. |
| `application-test.yml` | Test settings: tests use a separate `campus_test` database, so they never touch our data. |
| `db/migration/…platform_init.sql` | The first database script. It only starts the history of database changes. |
| `db/migration/…user_create_users.sql` | Creates the table of people who can log in. |

### Backend: code and tests (PR #13)
| File | What it does |
|---|---|
| `CampusHubApplication.java` | The "start button" of the server. |
| `SecurityConfig.java` | The guard at the door: decides which addresses are public, which need a login token and which are for admins only. Also scrambles passwords and signs login tokens (`04-password-jwt-roles.md`). |
| `AuthController.java` | A "ping" address that answers `pong`, to check the app runs. Login comes here next. |
| `package-info.java` (one per module) | A label on each folder: what it does, who owns it, what it may use. |
| `CampusHubApplicationTests` | Checks the app can start at all. |
| `SecurityConfigTest` | Checks public pages are open, everything else is locked, and students can't open admin pages. |
| `AuthControllerTest` | Checks ping, sign-up and login answer without a login, and that a bad form or a wrong password gets the right error code. |

### Backend: users (`03-user-entity.md`)
| File | What it does |
|---|---|
| `Role.java` | The two kinds of user: student and admin. |
| `User.java` | One user as the code sees it: email, scrambled password, name, role, blocked or not. |
| `UserRepository.java` | Finds a user by email, whatever capitals were typed. |
| `UserRepositoryTest` | Checks the lookup ignores capitals, that the same email can't be used twice, and that a user's creation time reads back exactly as it was saved. |
| `UserApi.java` | The "front desk" other parts of the app ask about users ("who is user 5, what's their e-mail?"). |
| `UserSummary.java` | The answer they get: id, e-mail, name, role. Never the password. |
| `UserService.java` | The person behind the front desk: really looks the user up and answers, or says "no such user". Also answers "who am I?" for the logged-in user. |
| `UserController.java` | The "who am I?" address (`/api/v1/users/me`): the website asks it after a page reload to know who is logged in. |
| `UserServiceTest` | Checks the answer for a known user, the "no such user" error, the "does it exist?" check, and "who am I?". |
| `RegisterRequest.java`, `LoginRequest.java` | The forms the website sends to sign up and to log in, with their rules (valid e-mail, password at least 8 characters). |
| `UserResponse.java`, `LoginResponse.java` | What the app answers: the user's details, and after login the entry ticket. |
| `AuthRequestsTest` | Checks the form rules (also a password that is too long in bytes) and that the answer never contains the password. |
| `AuthService.java` | Sign-up (always a student, refuses an email that's taken) and login (checks the password, hands out an entry ticket valid 8 hours). |
| `AuthServiceTest` | Checks sign-up stores a scrambled password, refuses a taken email, and that login gives a correct ticket or the same "wrong email or password" error for every mistake. |
| `AuthFlowIT` | Signs up, logs in and uses the ticket on the real app, like the website will (also "who am I?" with and without a ticket). |
| `UserSeeder.java` | On a laptop, creates 3 test accounts at startup (2 students, 1 admin, password `password`) if there are no users yet. |
| `UserSeederTest` | Checks the 3 accounts are created in an empty table, and nothing happens when users exist. |
| `AdminBootstrap.java` | On AWS, creates the first admin account at startup from the `ADMIN_EMAIL` / `ADMIN_PASSWORD` settings, if that account doesn't exist yet. |
| `AdminBootstrapTest` | Checks the admin is created with a scrambled password, an existing account is left alone, nothing happens when a setting is empty, and a too-short password stops the app. |

### Backend: login foundations (`04-password-jwt-roles.md`, `05-security-error-json.md`, `06-async-scheduling.md`, `07-user-api.md`, `08-auth-request-records.md`)
| File | What it does |
|---|---|
| `OpenApiConfig.java` | Adds the "Authorize" button to Swagger: paste a login token once, and every test call sends it. |
| `SecurityErrorHandler.java` | When the guard refuses someone ("log in first" or "not allowed"), it writes a clear error message with a short code, like every other error in the app. |
| `AsyncConfig.java` | Lets the app do work in the background or on a timer (late-return checks, moving bookings after damage). In tests, that work runs immediately. |
| `AsyncConfigTest` | Checks background work is switched on and runs immediately in tests. |
| `ErrorJsonTest` | Checks that wrong input (a missing field, broken data, a page that doesn't exist) still gets an error with its short code (`12-error-codes-fix.md`). |
| `SpaConfig.java` | On AWS, sends the website for every address that isn't a file or the API, so reloading a page like "My reservations" works (`19-dockerfile.md`). |
| `SpaFrontendTest` | Checks that the website and its pages load without a login, and that a missing file or an API path doesn't get the website by mistake. |
| `SecurityPassIT` | The security check-up: a student can't see or cancel another student's booking (it looks "not found"), can't book or report in someone else's name, can't open any admin page, and error messages never show program internals (`25-security-pass-tests.md`). |

### Backend: AWS adapters (`23-s3-ses-adapters.md`)
| File | What it does |
|---|---|
| `AwsConfig.java` | On AWS only: creates the connections to S3 (photos) and SES (e-mails). No keys: the AWS server lends its permissions. |
| `S3StorageService.java` | On AWS, saves incident photos in a private S3 bucket instead of a folder (same checks as on a laptop). |
| `SesNotificationService.java` | On AWS, sends real e-mails to users instead of writing them to the log. A failed e-mail is logged and never undoes the booking change. |
| `S3StorageServiceTest`, `SesNotificationServiceTest` | Check what we ask S3 and SES to do, without connecting to AWS. |
| `ProdProfileStartupTest` | Starts the whole app in AWS mode, so a missing piece shows up in CI instead of on the server. |

### Prototype mockup (`10-prototype-mockup.md`)
| File | What it does |
|---|---|
| `docs/prototype/campus-hub-prototype.html` | A clickable preview of the whole app with fake data. Open it by double-clicking; it needs no server. |

### `docs/`: team status
| File | What it does |
|---|---|
| `docs/plan-w4-w6.md` | The detailed plan for weeks 4–6, up to the first milestone (M1, 15 Nov). |
| `docs/whats-left.md` | One list per team member of what is still to do, week by week, until the defense. Each of us deletes a row when the PR that finishes it is merged. |

### `docs/`: AWS architecture (doc `09-aws-architecture.md`)
| File | What it does |
|---|---|
| `docs/diagrams/aws-architecture.drawio` + `.png` | The picture of how the app will run on AWS: one server, a database, HTTPS in front. |
| `docs/adr/001`–`005` | Five short "why we chose this" notes: work locally, one server instead of many parts, where passwords live, how the incident event travels, why the AWS account stays on the free plan. |

### Frontend: Reservations desk (`26-reservations-desk-page.md`)
| File | What it does |
|---|---|
| `frontend/src/pages/ReservationsDeskPage.tsx` | The admin's page at the lab counter (`/admin/reservations`): three lists (to pick up today, out now, overdue), a "Check out" button when a student takes the equipment and a "Return" button when they bring it back. On a phone each booking is a card. |
| `frontend/src/api/reservationDesk.ts` | The three calls the page makes to Calin's admin reservation endpoints: search, check out, return. |

### `documentation/Necula_Matei/` (my notes)
| File | What it does |
|---|---|
| `README.md` | The index of my notes: what I own, where to start reading, and one row per note with the PR it came with. |

### Not uploaded to GitHub (local only)
| What | Why |
|---|---|
| `backend/build/`, `backend/.gradle/` | Build output and Gradle's cache, recreated on every build. |
| `.idea/` | My IntelliJ settings. |
| `CLAUDE.local.md` | My private notes for Claude, hidden through `.git/info/exclude`. |
| `backend/uploads/` | Photos uploaded while testing locally. |

---

## Part 2: Technical details

### Repository root and `.github/`
| File | Key points |
|---|---|
| `AGENTS.md` | Starts with an override table: the plan PDFs say Maven / Java 21 / H2, the repo uses **Gradle / Java 25 / PostgreSQL 18**. Then: session start checks, the v2 ownership table, the "never" list, workflow, architecture rules, week plan, WAIT-or-go checks (`onmain`/`inmain` shell helpers), cross-module contracts. |
| `CODEOWNERS` | One line, `*` = all four of us, so GitHub requests every teammate on every PR (never the author). Since 4 Nov 2026 the `protect-main` ruleset no longer requires a code owner's approval: 1 approval from anyone is enough. Before that, every path had owner + review buddy (Matei ↔ Liviu, Calin ↔ Stefania). |
| `ci.yml` | One job named **`ci`**, which is the exact name the `protect-main` ruleset requires. A `postgres:18` service container with `campus_test`; `SPRING_DATASOURCE_*` env vars point the tests at it. Java 25 (Temurin) + `gradle/actions/setup-gradle@v6`, then `./gradlew build` and the JaCoCo report as an artifact. Every backend step has `if: hashFiles('backend/gradlew') != ''` and every frontend step `hashFiles('frontend/package.json')`, so docs-only PRs (like #12) still pass. |
| Templates | PR checklist uses `./gradlew build`; the task template's Definition of Done says "works from a fresh database (reset in pgAdmin)". |
| `.gitignore` / `.gitattributes` | Gradle paths (`build/`, `.gradle/`) with an exception for `gradle-wrapper.jar`; `gradlew text eol=lf` so the script runs on Linux/CI even when committed from Windows. |
| `Dockerfile` / `.dockerignore` | 3 stages: `node:24-slim` builds `frontend/dist`; `eclipse-temurin:25-jdk` caches Gradle dependencies, copies `dist` into `src/main/resources/static`, runs `bootJar` (no tests, CI ran them); `eclipse-temurin:25-jre-alpine` runs only `app.jar` as non-root user `app`, `SPRING_PROFILES_ACTIVE=prod`, `HEALTHCHECK` on `/actuator/health`, `-XX:MaxRAMPercentage=75`. `.dockerignore` keeps `.git`, `node_modules`, build output and `.env` files out. Details in `19-dockerfile.md`. |

### `build.gradle`
| Part | What and why |
|---|---|
| Plugins | `java`, `jacoco`, `org.springframework.boot` 4.1.1, `io.spring.dependency-management` (manages library versions), `com.diffplug.spotless` 8.10.4, `info.solidsoft.pitest` 1.19.0 (mutation testing, `33-mutation-testing.md`). |
| `java.toolchain` | `JavaLanguageVersion.of(25)`: compiles with JDK 25 whatever JDK runs Gradle. |
| Dependencies | Boot starters: webmvc, data-jpa, validation, security, oauth2-resource-server (JWT), flyway + `flyway-database-postgresql`, actuator. Plus springdoc **3.1.1** (the line for Boot 4), Lombok (compile only), DevTools (dev only), the PostgreSQL driver (runtime), and the matching `-test` starters. No H2. AWS SDK v2 through its BOM (`platform('software.amazon.awssdk:bom:2.57.0')`) with `s3` + `sesv2` for the prod adapters (`23-s3-ses-adapters.md`). |
| `spotless` | palantir-java-format 2.102.0 + remove unused imports. `build` runs `spotlessCheck`, so unformatted code fails CI; fix with `./gradlew spotlessApply`. |
| `test` task | `systemProperty 'spring.profiles.active', 'test'` makes every test use `application-test.yml`. `finalizedBy jacocoTestReport` writes coverage to `build/reports/jacoco/test/html/`. |
| `pitest` block | On-demand `./gradlew pitest` (not in `build` or CI): PIT 1.30.1 + `pitest-junit5-plugin` 1.2.3, `addJUnitPlatformLauncher = false` (use Boot's JUnit 6 launcher), all `com.campus.*` except main class, seeders and pure wiring config, start-up `@Bean` methods of `SecurityConfig` excluded, `*IT`/ArchUnit/start-up tests excluded, one thread per core, HTML + XML report in `build/reports/pitest/`, `test` profile. Details in `33-mutation-testing.md`. |

### `settings.gradle` and the wrapper
- `foojay-resolver-convention` 1.0.0 lets Gradle download a JDK 25 for the toolchain if none is installed. Without it, a laptop with only JDK 21 fails with "Cannot find a Java installation … matching languageVersion=25".
- Wrapper: Gradle 9.8.1. `gradlew` must be committed as **executable (mode 100755)**. IntelliJ generated it from Windows without the bit, and `./gradlew` failed with "Permission denied" until I ran `chmod +x`.

### Configuration files
| File | Setting | Why |
|---|---|---|
| `application.yml` | `profiles.default: dev` | "default", not "active": prod (and tests) can switch profile without editing the file. |
| | `jpa.open-in-view: false` | No lazy loading in controllers; entities are mapped to DTOs inside the service transaction. |
| | `hibernate.ddl-auto: validate` | Only Flyway creates tables; Hibernate checks the entities match and stops the app if not. |
| | no `mvc.problemdetails.enabled` (removed in `12-error-codes-fix.md`) | Turning it on adds Boot's own error handler, which answers MVC errors before Liviu's `GlobalExceptionHandler` and drops our `code`. Liviu's handler already writes RFC 9457 `ProblemDetail` JSON. |
| | `management…include: health,info` | Only the health check is exposed (the AWS load balancer uses it). |
| | `spring.task.execution` (`thread-name-prefix: campus-async-`, `pool.core-size: 2`) | The `@Async` pool Boot creates: small, and named so log lines show which ran in the background. |
| | `app.messaging.sqs.enabled: false` | Stretch-goal switch: false = Calin's local event listener triggers reassignment, true = my SQS bridge. Never both. |
| `application-dev.yml` | `url: ${DB_URL:jdbc:postgresql://localhost:5432/campus}` (+ `DB_USERNAME`, `DB_PASSWORD`, default `campus`) | Works with zero setup on every laptop; anyone with a different setup overrides it in their run configuration, never in git. |
| `application-prod.yml` | `${DB_URL}`, `${JWT_SECRET}`, `${PHOTO_BUCKET}`…; `${SQS_QUEUE_URL:}` | All secrets come from AWS. The `:` gives an empty default for optional values (SQS is a stretch goal). |
| `application-test.yml` | `campus_test` database | Tests never touch dev data. CI overrides the URL with env vars. |
| | `spring.test.database.replace: none` | `@DataJpaTest` would otherwise look for an embedded database (we have none) and fail. |
| Migration | `V2026_10_21_1500__platform_init.sql` → `SELECT 1;` | Starts `flyway_schema_history`. Name = `V` + timestamp + `__` + module + description, so two people never pick the same version. |
| Migration | `V2026_10_21_1528__user_create_users.sql` | `users` table: `email` UNIQUE (stored lowercase), `password_hash`, `full_name`, `role VARCHAR(32)`, `enabled`, `created_at TIMESTAMP`. First in the FK order. Details in `03-user-entity.md`. |

### Code
| File | Key points |
|---|---|
| `SecurityConfig` | One `SecurityFilterChain`. **Stateless** (no session) and **CSRF off**: CSRF abuses cookies the browser sends automatically, and our API uses no cookies, only the `Authorization: Bearer` header. Matchers are checked top to bottom, **first match wins**: `/api/v1/auth/**`, health and Swagger are public, `/error` is public (otherwise a 404 would show as 401), then `/api/v1/admin/**` → `hasRole("ADMIN")` (must stay above the next rule), `/api/v1/**` → any valid JWT, other `/api/**` and `/actuator/**` → `denyAll()`, then `GET /**` → `permitAll()` (the React build, ADR-002), anything else → `denyAll()`. `bearerTokenResolver` returns `null` on `/api/v1/auth/**`, so an old token can't make login/register fail with 401 (#116, `20-auth-ignores-old-token.md`). `JwtDecoder` and `JwtEncoder` share one HS256 key from `app.jwt.secret` (≥ 32 bytes); defining the decoder also stops Boot from creating a default user with a random password. Since #117 the converter (`activeUserFromToken`) looks the user up through `UserApi.findActiveRole` on every request: missing or disabled → 401, role from the database with the `ROLE_` prefix (`ObjectProvider`, so `@WebMvcTest` slices without `UserApi` still start; `22-token-account-check.md`). `PasswordEncoder` = delegating encoder (`{bcrypt}` hashes). 401/403 go to `SecurityErrorHandler` (wired on both the resource server and `exceptionHandling`). |
| `SecurityErrorHandler` | Implements `AuthenticationEntryPoint` (401 `UNAUTHORIZED`), `AccessDeniedHandler` (403 `FORBIDDEN`) and `RequestRejectedHandler` (400 `REQUEST_REJECTED` for URLs the firewall blocks; a `@Bean` in `SecurityConfig`, because Spring finds it by type, `21-firewall-error-json.md`), writing `ProblemDetail`-shaped JSON (`application/problem+json`) with Boot's Jackson 3 `JsonMapper`. Needed because filter errors never reach Liviu's `@RestControllerAdvice`. Created with `new` in `SecurityConfig`, so `@WebMvcTest` + `@Import(SecurityConfig.class)` still works. |
| `AsyncConfig` | `@EnableAsync` + `@EnableScheduling` (both off by default). A `@Profile("test")` `SyncTaskExecutor` bean makes `@Async` run on the caller's thread in tests, and replaces Boot's pool there. |
| `SpaConfig` | `WebMvcConfigurer` registering `/**` → `classpath:/static/` (replaces Boot's default handler for that pattern) with a `PathResourceResolver` subclass: existing file → the file; path starting `api/` or containing a dot → `null` (404); else `index.html` if present. Resource handlers run only after no controller matched. In dev `static/` is empty, so it changes nothing. Details in `19-dockerfile.md`. |
| `AwsConfig` | `@Profile("prod")`: `S3Client` and `SesV2Client` with `Region.of(app.aws-region)` and the SDK's default credentials chain (EC2 instance role; looked up on the first call, so the context starts without AWS). |
| `S3StorageService` | `@Profile("prod") StorageService`: Liviu's `StorageValidation` (type, 5 MB, magic bytes, key pattern), `PutObjectRequest` with the content type into `app.storage.photo-bucket`, `getObjectAsBytes` → `ByteArrayResource`, `NoSuchKeyException` → 404 `PHOTO_NOT_FOUND`, `deleteObject` (S3 is already a no-op for missing keys). |
| `SesNotificationService` | `@Profile("prod") NotificationService`: address from `UserApi.getById(id).email()`, `SendEmailRequest` with a simple UTF-8 subject + text body from `app.mail.from`. `SdkException` is caught and logged, so a failed e-mail can't roll back Calin's transaction. |
| `OpenApiConfig` | Annotations only: `@SecurityScheme(name = "bearerAuth", type = HTTP, scheme = "bearer", bearerFormat = "JWT")` + `@OpenAPIDefinition(security = @SecurityRequirement(name = "bearerAuth"))`, so the scheme applies to every endpoint and teammates don't annotate controllers. |
| `AuthController` | `GET /api/v1/auth/ping` returns the record `PingResponse("pong")`, which Spring turns into `{"message":"pong"}`. `POST /register` (`@ResponseStatus(CREATED)`) and `POST /login` take `@Valid @RequestBody` records and only call `AuthService`. Public through the `/api/v1/auth/**` rule. |
| `AuthService` | `register`: `existsByEmailIgnoreCase` → `409 EMAIL_TAKEN`, BCrypt hash, role always `STUDENT`, `saveAndFlush` inside a try so a simultaneous duplicate (UNIQUE column) also becomes 409. `login`: `findByEmailIgnoreCase` → `filter(isEnabled)` → `filter(passwordEncoder.matches)` → else `401 INVALID_CREDENTIALS` (same error for every case). Token: `JwtClaimsSet` with `sub` (id), `email`, `roles`, `iat`/`exp` from the shared `Clock` + `app.jwt.expiration` (8 h, bound as `Duration`), signed with the HS256 `JwtEncoder`. Details in `13-auth-register-login.md`. |
| `UserSeeder` | `@Component @Profile("dev") @Order(1)` `CommandLineRunner`: if `count() == 0`, `saveAll` student1 (id 1), student2 (id 2), admin (id 3), password `password` (one BCrypt hash), `created_at` from the `Clock`. Order 1 so Liviu's (2) and Calin's (3) seeders find users. Details in `14-user-seeder.md`. |
| `AdminBootstrap` | `@Component @Profile("prod")` `CommandLineRunner`, values from `app.admin.email` / `app.admin.password` (`@Value`, constructor). Blank → log + skip; `existsByEmailIgnoreCase` → skip (never resets a password or promotes a student); password < 8 chars or > 72 UTF-8 bytes → `IllegalStateException` (app doesn't start); else `save` one `ADMIN` with a BCrypt hash, `created_at` from the `Clock`. Logs the email, never the password. Details in `18-prod-admin-bootstrap.md`. |
| `Role` | In `user.api`, not `domain`: the shared `UserSummary` exposes it, and other modules may import only `api`. Saved as text (`@Enumerated(STRING)`); SecurityConfig adds `ROLE_` for `hasRole("ADMIN")`. |
| `User` | Entity: `@Getter`, protected no-args constructor for Hibernate, one public constructor that lowercases + trims the email and takes `createdAt` from the caller (`LocalDateTime.now(clock)`) and cuts it to whole seconds (#134, `24-user-created-at-seconds.md`). No setters. |
| `UserRepository` | `findByEmailIgnoreCase` (login), `existsByEmailIgnoreCase` (register → `409 EMAIL_TAKEN`). |
| `RegisterRequest` / `LoginRequest` | Records with Jakarta validation (contract §3): register = `@Email` ≤ 255, password 8–72, name ≤ 100, no `role` field (always `STUDENT`); login = only `@NotBlank` (same 401 for every wrong input). |
| `UserResponse` / `LoginResponse` | `UserResponse(id, email, fullName, role)` + `public static from(User)`, called inside the service transaction. `LoginResponse(accessToken, expiresIn, user)`, `expiresIn` in seconds. |
| `UserApi` / `UserSummary` | The agreed facade: `getById(id)` (throws `NotFoundException` `USER_NOT_FOUND`), `exists(id)`, `findActiveRole(id)` (role if the user exists and is enabled, used by `SecurityConfig`). The only user types other modules may import. Interface only for now: the implementation waits for Liviu's `NotFoundException`. |
| `UserService` | `@Service` implementing `UserApi`, so other modules get it when they inject `UserApi`. `getById` maps `User` → `UserSummary` inside a `@Transactional(readOnly = true)` method (open-in-view is off) and throws `NotFoundException("USER_NOT_FOUND", …)`; `exists` = `existsById`. Details in `11-user-service.md`. `me()` (not on `UserApi`, only for the controller) takes the id from the injected `CurrentUser` and returns a `UserResponse`, else `USER_NOT_FOUND`. Details in `17-users-me.md`. |
| `UserController` | `GET /api/v1/users/me` → `userService.me()`. No parameters at all: the id can only come from the token. Protected by the `/api/v1/**` rule (401 without a token). |
| `package-info.java` | One per module (`catalog`, `common`, `config`, `incident`, `messaging`, `notification`, `reassignment`, `reservation`, `storage`, `user`): purpose, **v2 owner** (for example `common` = Liviu for error/paging/audit, Calin for time, Stefania for security), sub-packages, and which modules it may and may not depend on. |

### Prototype mockup
| File | Key points |
|---|---|
| `campus-hub-prototype.html` | One file, plain HTML/CSS/JS, no libraries. In-memory fake data from `seed()`, clock fixed at Mon 2 Nov 2026 09:15. Every click goes through `api()`, which logs the REST call from `docs/api-contract.md` and shows errors as ProblemDetail with a `code`. Booking checks follow Calin's order; the engine tries `SameModelStrategy` then `SameCategoryStrategy`, else force-cancels. Rule numbers sit in `RULES` (🟡 in `docs/decisions.md` §5). Details in `10-prototype-mockup.md`. |

### `docs/adr/` and the AWS diagram
| File | Key points |
|---|---|
| `aws-architecture.drawio` | CloudFront → EC2 `t3.small` (Docker, API + React build) → RDS PostgreSQL 18 in private subnets; S3, SES, SSM Parameter Store, ECR, CloudWatch Logs; deploy from GitHub Actions through ECR + SSM Run Command. No ECS, ALB or NAT |
| ADRs | Format: Status, Context, Decision, Why, Consequences (+ Alternatives). Each one has a matching row in `docs/decisions.md` §4. Details in `09-aws-architecture.md` |

### Frontend: Reservations desk
| File | Key points |
|---|---|
| `ReservationsDeskPage.tsx` | Tabs = one status each (`CONFIRMED` / `CHECKED_OUT` / `OVERDUE`); only "To pick up" sends `date` (default: today in Europe/Bucharest via `Intl.DateTimeFormat('en-CA', …)`). One effect loads the open tab (`ignore` flag against stale answers, `loading` ends in `finally` only for the latest request), a second one loads the tab counts with `size=1`. Asset names via Calin's `fetchAssetSummary` (Liviu's `GET /api/v1/assets/{id}`); students as `Student #id` (no admin user lookup endpoint yet). Buttons call check-out / return, then reload; 409s show the text from `errorMessages.ts` with "Could not … reservation #id" as title. `<PagedTable>` on desktop, a local `PhoneList` (same columns, one card per booking) below 48em. Details in `26-reservations-desk-page.md`. |
| `reservationDesk.ts` | `searchReservations({ status, date, page, size })` → `GET /api/v1/admin/reservations`, `checkOutReservation(id)` → `POST …/{id}/check-out`, `returnReservation(id)` → `POST …/{id}/return`. Types (`Reservation`, `ReservationStatus`) imported from Calin's `src/api/reservations.ts`, not copied. |

### Tests
| Test | What it proves |
|---|---|
| `CampusHubApplicationTests` | `@SpringBootTest`: the whole app starts, connects to PostgreSQL and runs Flyway. |
| `SecurityConfigTest` | `@SpringBootTest` + MockMvc: health and Swagger return 200; a made-up probe path (`/api/v1/_security-probe`, so teammates' real endpoints can't change the result) without a token, or with a fake one, returns 401 with `code = UNAUTHORIZED` JSON. `@Transactional`; each token belongs to a real user saved by the test (rolled back). With real tokens signed by our `JwtEncoder`: a disabled or deleted user gets 401, a token claiming ADMIN for a student gets 403; a student gets 403 (`code = FORBIDDEN`) on `/api/v1/admin/_security-probe`, an admin gets through (404), any token gets 403 on other `/api` paths and on a `POST` outside the API; a broken token on `/api/v1/auth/ping` is ignored (200); `/api/v1/auth/ping;x=1` (blocked by the firewall) → 400 `REQUEST_REJECTED` JSON. Also checks BCrypt hashes and the Swagger `bearerAuth` scheme. |
| `SecurityPassIT` | `@SpringBootTest` + MockMvc + `@Transactional` (rolled back), real users and login tokens, asset + booking created through Liviu's and Calin's REST endpoints: student B gets 404 `RESERVATION_NOT_FOUND` on A's reservation (`GET`, `cancel`), same as for a missing id; B's `/me` lists are empty; `userId` / `reportedById` in the body are ignored. Every `/api/v1/admin/**` mapping read from `RequestMappingHandlerMapping` (16 on `main`) → student 403 `FORBIDDEN`, no token 401 `UNAUTHORIZED`. 9 error cases + a test-only endpoint throwing a `DataIntegrityViolationException` → ProblemDetail with `code`, no stack trace, class name or SQL. |
| `ErrorJsonTest` | `@SpringBootTest` + MockMvc with a test-only `@RestController` (`@Import`, since `main` has no `@Valid` endpoint yet) and `jwt()` from spring-security-test as the token: blank field → 400 `VALIDATION_FAILED` + `errors[].field`; broken JSON → 400 `MALFORMED_REQUEST`; unknown path → 404 `NOT_FOUND`. All three failed before the fix. |
| `SpaFrontendTest` | `@SpringBootTest` + MockMvc with `test/resources/static/index.html` as a stand-in build: `/` forwards to `index.html` (Boot's welcome page; MockMvc doesn't follow forwards), `/reservations` and `/admin/incidents` return it without a token, `/assets/index-old.js` → 404, an unknown `/api/v1` path → 401 JSON, not HTML. |
| `S3StorageServiceTest` | Mockito `S3Client`: store captures bucket, generated key and content type; a fake PNG and a traversal key never reach S3; load returns the bytes, a missing key is `PHOTO_NOT_FOUND`; delete sends the exact request. |
| `SesNotificationServiceTest` | Mockito `SesV2Client` + `UserApi`: the captured request has the user's address, sender, subject and body; an `SesV2Exception` doesn't propagate. |
| `ProdProfileStartupTest` | `@SpringBootTest` + `@ActiveProfiles("prod")` with stand-ins for the env variables: the context starts and `StorageService` / `NotificationService` are the S3 / SES versions (#115). |
| `AsyncConfigTest` | `@SpringBootTest` with a test-only `@Async` bean: it is a proxy (async on) and runs on the test thread (sync executor). Its value is read through a method, because fields of a Spring proxy stay empty. |
| `AuthRequestsTest` | Plain unit test (no Spring, `Validation.buildDefaultValidatorFactory()`): the record rules and the `UserResponse` mapper. |
| `AuthControllerTest` | `@WebMvcTest(AuthController.class)` starts only the web layer (fast, no database). It doesn't load our `@Configuration` classes, so `@Import(SecurityConfig.class)` is needed; without it Spring's default security would answer 401. Teammates copy this pair of annotations for their controller tests. `AuthService` is a `@MockitoBean`; tests: ping, register 201 (no `passwordHash` in the JSON), bad email + short password → 400 `VALIDATION_FAILED` with 2 field errors, login 200, wrong password → 401 `INVALID_CREDENTIALS`. |
| `UserServiceTest` | Plain Mockito (`@ExtendWith(MockitoExtension.class)`, no Spring): known id → `UserSummary` without the hash; unknown id → `NotFoundException` with code `USER_NOT_FOUND`; `exists` follows the repository; `me()` with a mocked `CurrentUser` (found / `USER_NOT_FOUND`); `findActiveRole` (enabled → role, disabled or missing → empty). The id is set with `ReflectionTestUtils` because only the database sets it. |
| `AuthServiceTest` | Mockito, only `UserRepository` mocked; the real BCrypt encoder and a real `NimbusJwtEncoder` with a test key. The token is decoded with a `JwtTimestampValidator` on the fixed clock, so claims and expiry are checked exactly. 7 tests: register OK / taken / race, login OK / wrong password / unknown email / disabled. |
| `AuthFlowIT` | `@SpringBootTest` + MockMvc + `@Transactional` (rolled back): register → login → token gets 404 on an unknown `/api/v1` path (past security) and 403 on `/api/v1/admin/**`; a second register with other capitals → 409 `EMAIL_TAKEN`; `GET /api/v1/users/me` with the token → 200 (own email, `STUDENT`, no `passwordHash`), without → 401 `UNAUTHORIZED`; login while an expired token is still sent → 200. |
| `UserSeederTest` | Mockito: `count() = 0` → `saveAll` captured (`ArgumentCaptor`), emails + roles in order, all hashes match `password`; `count() = 3` → `saveAll` never called. |
| `AdminBootstrapTest` | Mockito, real BCrypt encoder: missing → one `ADMIN` saved, email lowercased + trimmed, hash ≠ password and matches it; existing email → no `save`; blank email or password → no repository call; `"short"` → `IllegalStateException`, no `save`. |
| `UserRepositoryTest` | `@DataJpaTest`: Flyway + JPA only, on `campus_test`, rolled back after each test. Finds a user with different capitals; a duplicate email with other capitals throws `DataIntegrityViolationException` (`saveAndFlush` forces the INSERT inside the test). Saving a user with nanoseconds, then reading it back after `entityManager.clear()`, gives the same whole-second `createdAt` (#134). |

Result: `./gradlew build` → BUILD SUCCESSFUL, 32 tests (6 after #13, +2 for users, +9 from Liviu's #22, +12 for login foundations, +3 for `UserService`), locally on PostgreSQL 18.

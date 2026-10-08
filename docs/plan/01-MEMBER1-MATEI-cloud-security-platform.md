# Member 1: Matei: Cloud, Security, User & Platform Lead

> Read `00-TEAM-HANDBOOK.md` first. This file only covers what is specific to you.
> You are the **first domino**: for the first 3 weeks, everyone else is blocked or slowed down until your skeleton, auth and shared interfaces exist. After week 3 your role shifts to **AWS + technical anchor (unblocker/reviewer)**.

---

## 1. Project in one paragraph

Campus Equipment & Incident Hub: students book lab equipment by time slot (overlaps rejected) and report broken equipment with photos. Damaged assets are locked automatically. An **auto-reassignment engine** moves future bookings of a broken asset to a substitute, or force-cancels them, and notifies the students. Spring Boot 4.1 / Java 21 backend (modular monolith), React frontend, H2 locally and PostgreSQL on AWS. You make it **runnable, secure, consistent and deployable**.

## 2. What you own

| Area | Contents |
|---|---|
| **Project platform** | Repo, branch protection, `CODEOWNERS`, templates, Spring Initializr skeleton, `pom.xml` (sole editor), `application*.yml`, Spotless, JaCoCo, ArchUnit rule, README/CONTRIBUTING |
| **`common/`** | `error/` (exceptions + global `@RestControllerAdvice` → ProblemDetail), `time/` (`Clock` bean), `paging/` (`PageResponse<T>`), `security/CurrentUser`, `audit/` (`AuditService`, `audit_log` table, admin endpoint) |
| **`config/`** | `SecurityConfig`, JWT encoder/decoder, `OpenApiConfig` (Bearer auth button in Swagger), `AsyncConfig`, dev H2-console security chain |
| **`user/`** | `User` entity, `Role`, register/login, `GET /users/me`, `UserApi` facade, admin bootstrap, user seeder |
| **`storage/`** | `StorageService` + `LocalStorageService` (dev) + `S3StorageService` (prod) |
| **`notification/`** | `NotificationService` + `LoggingNotificationService` (dev) + `SesNotificationService` (prod) |
| **`messaging/`** | prod-only SQS bridge (event → SQS) and consumer (SQS → `ReassignmentService`), DLQ handling |
| **DevOps** | `.github/workflows/ci.yml`, `deploy.yml`, `Dockerfile`, optional `docker-compose.yml` |
| **AWS (`infra/`)** | Terraform: VPC, ALB, ECS Fargate, ECR, RDS, S3 (photos + frontend), CloudFront, SQS + DLQ, SES, SSM/Secrets, IAM, CloudWatch, Budgets |
| **Docs** | Component diagram, Deployment/AWS diagram, security & deployment chapters, architecture decisions (ADRs), runbook |

**Why you also own Java code (`user/`, `common/`, `storage/`, `notification/`, `messaging/`):** FIS grades Java, design and tests. If your commits are only YAML and Terraform, it looks like sysadmin work. Your Java code has its own patterns to present at the defense: **Strategy/Adapter via profiles** (Local vs S3, Logging vs SES), a **global exception handler**, and **audit logging**.

---

## 3. How your work connects to everyone

| Member | What they need from you | When (latest) | What you need from them |
|---|---|---|---|
| **All** | Runnable skeleton, CI, README, coding conventions | **End W1** | GitHub usernames, installed tools |
| **All** | `common.error` exceptions + handler, `Clock`, `PageResponse`, `CurrentUser`, security rules (`/api/v1/admin/**`) | **Mid W3** | Their error `code` values for `api-contract.md` |
| **All** | Login working + seed users + Swagger "Authorize" button → they can test protected endpoints | **End W3** | — |
| **All** | Dependencies added on request (`pom.xml`) | within 24h | An issue labeled `dependency` |
| **Member 2 (Catalog)** | `AuditService` (for admin changes), seeder pattern with `@Order` | W3–W4 | `CatalogApi` stable (used by M3/M4) |
| **Member 3 (Reservations)** | `UserApi`, `NotificationService` (overdue / cancel notices), `Clock` (critical for them), scheduling enabled (`@EnableScheduling`) | W3–W5 | List of notifications they send |
| **Member 4 (Incidents/Frontend)** | `StorageService` (photos), `NotificationService`, async/event infra, **SQS bridge** in prod, JWT format (claims) for the frontend, Vite proxy agreement, frontend hosting on S3/CloudFront | Storage W4, SQS W9, hosting W7 | `AssetDamagedEvent` record (must be JSON-serializable for SQS), frontend build command/output dir |

**Your JWT contract with the frontend** (write it in `docs/api-contract.md`):
- `POST /api/v1/auth/login` → `{ "accessToken": "...", "expiresIn": 3600, "user": { "id":1, "email":"...", "fullName":"...", "role":"STUDENT" } }`
- Claims: `sub` = user id (string), `email`, `roles` = `["STUDENT"]`, `iat`, `exp`. Token lifetime: 8h (simple: no refresh tokens for this project; document it as a known limitation).
- `POST /api/v1/auth/register` → creates a `STUDENT` only. **Admins can never self-register.**

---

## 4. Step-by-step guide

### Week 1: Repo and skeleton (top priority, others wait for this)
1. Create the GitHub repo `campus-hub` and add the 3 teammates as collaborators (or create an organization).
2. Settings → Branches → protect `main`: require PR, 1 approval, status checks (`ci`) required, require up-to-date branch, disallow force pushes. Enable **secret scanning** + push protection, and Dependabot alerts.
3. Generate the backend at start.spring.io: Maven, Java 21, **Spring Boot 4.1.x**, group `com.campus`, artifact `campus-hub`, package `com.campus`. Dependencies are listed in handbook §4.1. Unzip into `backend/`.
4. Add `.gitignore`, `.gitattributes`, `.editorconfig` (handbook §5.2–5.3). Run `git update-index --chmod=+x backend/mvnw`.
5. Create the empty package structure (handbook §5) with a `package-info.java` in each module, so everyone sees where their code goes.
6. `application.yml` (shared):
   ```yaml
   spring:
     application.name: campus-hub
     profiles.default: dev          # "default", NOT "active": prod can override it
     jpa:
       open-in-view: false
       hibernate.ddl-auto: validate
     servlet.multipart: { max-file-size: 5MB, max-request-size: 6MB }
     mvc.problemdetails.enabled: true
   management.endpoints.web.exposure.include: health,info
   app:
     time-zone: Europe/Bucharest
     jwt: { expiration: 8h }
   ```
   `application-dev.yml`:
   ```yaml
   spring:
     datasource:
       url: jdbc:h2:file:./data/campusdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH
       username: sa
       password:
     h2.console.enabled: true
     jpa.show-sql: true
   app:
     jwt.secret: dev-only-secret-change-me-dev-only-secret-change-me-0123456789   # ≥ 32 bytes for HS256
     storage.local-dir: ./uploads
   ```
   `application-prod.yml`: everything from env vars: `${DB_URL}`, `${DB_USERNAME}`, `${DB_PASSWORD}`, `${JWT_SECRET}`, `${PHOTO_BUCKET}`, `${SQS_QUEUE_URL}`, `${AWS_REGION}`, `${ADMIN_EMAIL}`, `${ADMIN_PASSWORD}`, `${MAIL_FROM}`. Commit a `.env.example` documenting them (no values).
   **Note:** Spring does **not** read `.env` files by itself. Dev needs zero env vars because of the defaults above.
7. Add Spotless (palantir-java-format) + JaCoCo to `pom.xml`. Run `./mvnw spotless:apply` once and commit.
8. `ci.yml`: on `pull_request` and push to `main`: checkout → setup-java 21 (temurin, cache maven) → `./mvnw -B verify` (includes `spotless:check`) → setup-node 24 → `npm ci && npm run build` in `frontend/` (once it exists). Add a **PostgreSQL 17 service container** and set `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/campus` (+ username/password) for the job, so `@SpringBootTest` tests and Flyway migrations run on real Postgres. (`@DataJpaTest` replaces the datasource with an embedded DB by default. That's fine; those stay fast on H2.)
9. `CODEOWNERS`, PR template, issue templates, README ("run in 3 commands"), CONTRIBUTING (copy handbook §7 & §9 summary).
10. Push a "hello" endpoint (`GET /api/v1/auth/ping`) and ask all 3 teammates to clone, run, and open their first practice PR. **Don't move on until all 4 laptops run the app.**
11. Optional: `docker-compose.yml` with `postgres:17` for anyone who wants to test locally on Postgres (`SPRING_PROFILES_ACTIVE=dev` + overridden URL).

### Week 2: Design (with the team)
1. Write the security rules and the error format into `docs/api-contract.md`.
2. Design `common` (exception classes, error codes, `PageResponse`, `CurrentUser`, `AuditService`, `Clock`).
3. Lead the **contracts session**: everyone writes their `api` interface signatures (handbook §11).
4. Prepare the **entity freeze** session (handbook §10): you drive it; everyone types their own entity.
5. Draft the AWS architecture diagram (draw.io) + ADRs: "local-first", "ECS public subnets without NAT (budget) vs. private + NAT (ideal)", "SSM Parameter Store vs Secrets Manager", "Spring events + SQS bridge".
6. **AWS account hygiene now** (before any resource): MFA on root, stop using root; create an admin user via IAM Identity Center; create an **AWS Budget** ($5 and $20 alerts by email); choose region **eu-central-1** (Frankfurt). Check which Free Tier your account has: accounts created after 15 July 2025 get a **credit-based free plan** instead of the old 12-month free tier, so read the conditions in Billing.

### Week 3: Foundations (deadline for unblocking others)
1. **User + auth:**
   - Migration `users` table; `User` entity; `Role` enum; `UserRepository.findByEmailIgnoreCase`.
   - `PasswordEncoder` bean: `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (BCrypt).
   - `AuthService.register` (normalize email to lowercase, check uniqueness → 409 `EMAIL_TAKEN`, role always `STUDENT`), `login` (wrong email or wrong password → the **same** 401 message, don't reveal which).
   - JWT with **`spring-boot-starter-oauth2-resource-server`** (no custom filter):
     ```java
     @Bean JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }
     @Bean JwtDecoder jwtDecoder(SecretKey key) {
         return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); }
     // when encoding, the header MUST say HS256, otherwise: "Failed to select a JWK signing key"
     JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
     jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
     ```
     Authorities converter: `JwtGrantedAuthoritiesConverter` with `setAuthoritiesClaimName("roles")` and `setAuthorityPrefix("ROLE_")`, so `hasRole("ADMIN")` works.
   - `SecurityConfig`. **Order matters: the first matching rule wins.** (The Gemini snippet had a bug: its `GET /api/v1/**` rule came before the admin rule, so any student could GET admin endpoints.)
     ```java
     http.csrf(c -> c.disable())                       // stateless JWT API, no cookies → CSRF not needed
         .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
         .authorizeHttpRequests(auth -> auth
             .requestMatchers("/api/v1/auth/**").permitAll()
             .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
             .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
             .requestMatchers("/api/v1/**").authenticated()
             .anyRequest().denyAll())
         .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtAuthConverter())));
     ```
     Separate `@Profile("dev")` chain with `@Order(0)` and `securityMatcher(PathRequest.toH2Console())`: permitAll, CSRF off, `frameOptions(sameOrigin)`. Otherwise the H2 console shows a blank page.
   - `CurrentUser` helper: reads `SecurityContextHolder` → `Jwt` → `sub` as `Long`; `isAdmin()`.
   - Tests: register/login unit tests; `@WebMvcTest` for 401 on protected and 403 on admin as student; one test proving **a student cannot reach `/api/v1/admin/**`**.
2. **`common`:** exceptions + `GlobalExceptionHandler` (`@RestControllerAdvice` returning `ProblemDetail` with a `code` property). Also map `MethodArgumentNotValidException` → 400 with a field-error list, `ObjectOptimisticLockingFailureException` → 409, `AccessDeniedException` → 403, `MaxUploadSizeExceededException` → 413 `PHOTO_TOO_LARGE`, malformed JSON/dates (`HttpMessageNotReadableException`) → 400, and everything unexpected → 500 with a generic message (log the stack trace; **never** send it to the client).
   - `AsyncConfig`: `@EnableAsync` + `@EnableScheduling` with a small named thread pool. In the `test` profile, provide a synchronous executor (or tell the team to use Awaitility), so Member 4's async event tests are not flaky.
3. **`Clock` bean:** `Clock.system(ZoneId.of(appTimeZone))`. Post the rule in chat: *"never `LocalDateTime.now()` without `clock`"*.
4. **Stubs** for `StorageService`, `NotificationService` (log only), `AuditService` (log only), so M2–M4 can inject them immediately.
5. **Seeder pattern:** `@Profile("dev") @Order(1) UserSeeder implements CommandLineRunner` (admin + 2 students, if `count()==0`). Share it as the template.
6. **Admin bootstrap (prod):** on startup, if no admin exists and `ADMIN_EMAIL`/`ADMIN_PASSWORD` are set, create one.
7. `OpenApiConfig`: a `bearerAuth` security scheme, so Swagger has an **Authorize** button. Tell everyone how to use it: login → copy the token → Authorize.
8. Merge the **entity freeze PR** (all members) + tag the commit.

### Weeks 4–5: Quality + AWS foundations
1. ArchUnit test: `com.campus.(*)..` may only access other modules' `..api..` packages (plus `common`, `config`). Turn it on as a **warning first**, then enforce after W6. This prevents spaghetti.
2. Real `AuditService` (`audit_log` migration, `GET /api/v1/admin/audit-log` paginated). Real `LocalStorageService`:
   - Generate the key yourself: `UUID + extension from a whitelist`. **Never** use `getOriginalFilename()` as a path (path traversal: `../../etc/passwd`). Validate the content type (`image/jpeg|png|webp`) **and** check magic bytes if possible.
   - Store under `./uploads/<yyyy>/<mm>/<uuid>.jpg`; create directories on startup.
   - `load(key)` must reject keys containing `..` or `/` outside the pattern.
3. Terraform setup in `infra/`: remote state in an S3 bucket (with `use_lockfile = true`, versioning on), `provider "aws"` with `default_tags { Project = "campus-hub" }` (tags let you find everything in Cost Explorer). Files: `network.tf`, `security_groups.tf`, `rds.tf`, `s3.tf`, `ecr.tf`, `iam.tf`, `ssm.tf`, `budgets.tf`, `outputs.tf`, `variables.tf`.
4. VPC: `10.0.0.0/16`, 2 AZs, public subnets (ALB + ECS tasks with public IPs, **no NAT gateway**, to save money), isolated DB subnets (no internet route). RDS: PostgreSQL 17, `db.t4g.micro`, 20 GB gp3, single-AZ (write "Multi-AZ in production" in the report), `publicly_accessible = false`, encrypted, backups 1–7 days, `deletion_protection = false` for the course, final snapshot skipped/handled.
5. Security groups chain: ALB SG (80/443 from CloudFront prefix list `com.amazonaws.global.cloudfront.origin-facing`, or 0.0.0.0/0 while starting) → ECS SG (8080 **from ALB SG only**) → RDS SG (5432 **from ECS SG only**).
6. Help the team: review every PR in your areas within 24h, and run the "integration" on `main` yourself every few days.

### Week 6: Container (M1 week)
1. Multi-stage `backend/Dockerfile`:
   ```dockerfile
   FROM eclipse-temurin:21-jdk AS build
   WORKDIR /app
   COPY .mvn .mvn
   COPY mvnw pom.xml ./
   RUN ./mvnw -B -q dependency:go-offline
   COPY src src
   RUN ./mvnw -B -q package -DskipTests
   FROM eclipse-temurin:21-jre
   RUN useradd --system --uid 1001 app
   WORKDIR /app
   COPY --from=build /app/target/*.jar app.jar
   USER app
   EXPOSE 8080
   ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
   ENTRYPOINT ["java","-jar","/app/app.jar"]
   ```
   Build in CI (linux/amd64), or locally with `--platform linux/amd64` if you're on Apple Silicon. Otherwise Fargate fails with `exec format error`. (Alternative: run Fargate on ARM64/Graviton, which is cheaper, but then build arm64 consistently.)
2. ECR repository with a lifecycle policy (keep the last 10 images).
3. Run the image locally against the optional docker-compose Postgres with `SPRING_PROFILES_ACTIVE=prod` and env vars. That's your rehearsal for ECS.

### Weeks 7–8: Deploy
1. **ECS Fargate:** cluster, task definition (0.5 vCPU / 1 GB), `awslogs` → CloudWatch log group with **7-day retention**, env vars + secrets from **SSM Parameter Store SecureString** (free; Secrets Manager costs $0.40/secret/month, but mention its rotation feature in the report). One task normally (`desired_count = 1`); show 2 tasks for the HA demo.
   - **Task role** (app permissions): `s3:PutObject/GetObject/DeleteObject` on the photos bucket only, `sqs:SendMessage/ReceiveMessage/DeleteMessage/GetQueueAttributes` on your queue only, `ses:SendEmail`.
   - **Execution role** (ECS agent): pull from ECR, write logs, read the SSM parameters (+ `kms:Decrypt`). Mixing up the two roles is the classic ECS mistake.
2. **ALB:** target group type `ip`, port 8080, health check `/actuator/health`, healthy codes `200`. ECS service `health_check_grace_period_seconds = 120`. Spring Boot needs 10–30 s to start, and without the grace period ECS kills tasks in an endless loop.
3. **Frontend hosting:** private S3 bucket + CloudFront with **Origin Access Control**. Behaviors:
   - default `/*` → S3 (cache on).
   - `/api/*` → ALB origin, **CachingDisabled** cache policy, origin request policy forwarding all viewer headers/query strings/cookies. **Verify that the `Authorization` header reaches Spring** (call `/api/v1/users/me` through CloudFront). If you get 401 through CloudFront but 200 directly on the ALB, the header is being dropped: adjust the policy.
   - **SPA routing:** do **not** use distribution-wide custom error responses (403/404 → `/index.html`). They would also replace your API's JSON 404s with HTML. Use a **CloudFront Function** on the default behavior that rewrites paths without a file extension to `/index.html`.
   - The default `*.cloudfront.net` domain gives free HTTPS. A custom domain needs Route 53 + an ACM certificate **in us-east-1** (CloudFront requirement).
4. **`deploy.yml`** (on push to `main`, after CI passes):
   - Authenticate to AWS with **GitHub OIDC** (an IAM role trusted for your repo's `main` branch). **No long-lived AWS keys in GitHub secrets.**
   - Backend: build → push the image tagged with the commit SHA (not only `latest`) → render the task definition → `aws ecs update-service` / `amazon-ecs-deploy-task-definition` action and wait for stability.
   - Frontend: `npm ci && npm run build` → `aws s3 sync dist/ s3://… --delete` → CloudFront invalidation `/*` (or `/index.html` only, with hashed assets).
5. **`S3StorageService`** (`@Profile("prod")`): backend-proxied upload (`putObject`) with the same interface as local. Reading: return a **pre-signed GET URL** (5–15 min) in the incident DTO, or stream through the backend. Pre-signed **uploads** directly from the browser are a stretch goal (they need S3 CORS + 2-step frontend logic).
6. First full AWS deploy of the M1 version **by the end of W8 at the latest**. Deploy early: the first deploy always surfaces 3–5 surprises.

### Week 9: Decoupling (M2)
1. SQS standard queue `incident-reassignment-queue` + DLQ (`maxReceiveCount = 3`), visibility timeout longer than the max processing time (e.g. 60 s), long polling 20 s.
2. `messaging` (`@Profile("prod")`):
   - `SqsBridge`: `@TransactionalEventListener(phase = AFTER_COMMIT)` on `AssetDamagedEvent` → serialize to JSON → `sendMessage`.
   - `SqsConsumer`: a polling loop (`@Scheduled(fixedDelay=…)` + `receiveMessage(waitTimeSeconds=20)`), or Spring Cloud AWS `@SqsListener` **if a version compatible with Boot 4.1 exists** (check first; otherwise use plain SDK v2). Call `ReassignmentService.handle(event)`; **delete the message only after success**.
   - Make sure the **local listener of Member 4 is `@Profile("!prod")`** so reassignment doesn't run twice.
   - The dev and prod paths call the **same** `ReassignmentService.handle()`. Agree on this with Member 4.
3. `SesNotificationService` (`@Profile("prod")`): SES starts in the **sandbox**, which only sends to verified addresses. Verify the 4 team emails + the professor's (if they agree) for the demo. Production access is not needed. Resolve user email via `UserApi`. Notification failures must **not** roll back business transactions: send after commit, catch and log.
4. Optional: SNS topic + CloudWatch alarms (ALB 5xx, unhealthy targets, RDS CPU > 80%, DLQ messages > 0) → email.

### Week 10: Hardening (feature freeze)
- Load smoke test (e.g. `k6` or `hey` against the ALB, 50 users booking): find timeouts and connection pool limits.
- Security pass: try IDOR with two student tokens on every endpoint; check that admin endpoints reject students; make sure no stack traces leak; CORS is not needed (same origin).
- Verify DB backups/snapshots exist; practice "restore to a point in time" once (good SAA material, and a nice report paragraph).
- Cost check in Cost Explorer (filtered by tag `Project`).

### Week 11–12: Docs & demo
- Deployment diagram (AWS), component diagram (modules), CI/CD pipeline description, security chapter (JWT flow sequence diagram, RBAC table, threat list), runbook ("how to deploy / roll back / tear down").
- Demo plan: AWS live + **local fallback ready on the same laptop** + a recorded video backup.
- After grading: `terraform destroy` (keep a final RDS snapshot if you want), delete ECR images, check the bill a few days later.

---

## 5. AWS cost guardrails (read before creating anything)

Rough monthly costs if everything runs 24/7 (eu-central-1, on-demand; check current prices):

| Resource | ≈ $/month | How to save |
|---|---|---|
| ALB | 18–22 | Only run it during dev/demo weeks |
| Fargate 0.5 vCPU/1 GB × 1 task | 15–20 | `desired_count = 0` when idle; Fargate Spot |
| RDS db.t4g.micro + 20 GB | 13–16 (or free-tier/credits) | Stop the instance when idle. **It auto-starts after 7 days!** |
| NAT Gateway | 35+ | **Don't create one.** ECS in public subnets, SG-locked |
| Public IPv4 per task/ALB IP | ~3.6 each | — |
| Interface VPC endpoints | ~7–8 each per AZ | Not needed with the public-subnet design |
| S3, CloudFront, SQS, SES, SSM | ~0–1 | — |

- Set **AWS Budgets** alerts at $5 / $20 / $40 on day 1.
- Use `terraform destroy` / `apply` between work phases. Make infra fully reproducible so this is painless.
- **Never** commit `*.tfstate` (it contains the DB password). It goes in the S3 backend.
- Delete forgotten resources: Elastic IPs, snapshots, ECR images, log groups without retention.

---

## 6. Things to keep in mind

- **Unblock, don't take over.** When a teammate is stuck, explain for 15 minutes; they type the code. Their commits must be theirs (grading + defense).
- **You are the only `pom.xml` editor.** Batch dependency requests. Announce every dependency change in chat ("pulled main? run `./mvnw clean`").
- **Keep `main` green.** You will notice CI failures first; ping the author kindly and quickly.
- **Don't let AWS eat your Java time.** Time-box AWS tasks; if one is blocked for more than 4h, write it down and move to Java work.
- **Never give teammates AWS credentials.** They don't need them; everything runs locally.
- **Profiles must be mutually exclusive:** every dev/prod pair (`Local`/`S3`, `Logging`/`SES`, local listener/SQS bridge) uses `@Profile("prod")` vs `@Profile("!prod")`. Never `dev` vs `prod` (the `test` profile would then get neither bean). Make the app **fail fast** at startup if a prod env var is missing.
- **Secrets:** dev secrets in `application-dev.yml` are fine (fake). Prod secrets only in SSM. The JWT secret must be ≥ 256 bits (32+ random bytes, e.g. `openssl rand -base64 48`).
- **WSL tip (your setup):** keep the repo inside the Linux filesystem (`~/…`), not `/mnt/c/…` (very slow builds, file-watching issues). Use IntelliJ's WSL support or run IntelliJ inside WSLg. Docker Desktop needs WSL integration enabled for your distro.
- **The 3 places where Spring Security beginners lose days:** matcher order; the `ROLE_` prefix (`hasRole("ADMIN")` checks the `ROLE_ADMIN` authority); the JWS header algorithm. Debug with `logging.level.org.springframework.security=DEBUG` (dev only).

---

## 7. Foreseeable problems (yours)

| Problem | Symptom | Cause | Fix / prevention |
|---|---|---|---|
| Teammates can't run the skeleton | Errors on day 1 | Wrong JDK, `JAVA_HOME`, CRLF in `mvnw` | Install guide in README; `.gitattributes`; `./mvnw -v` check |
| 403 on every request after login | Even valid tokens fail | Missing `ROLE_` prefix / wrong claim name in the converter | `JwtGrantedAuthoritiesConverter` config; a test with a real token |
| 401 with a valid-looking token | `Invalid signature` / `Failed to select a JWK` | Header not HS256 / secret too short / different secret dev vs. test | Fix the JWS header; ≥32-byte secret |
| Students can access admin GETs | Security hole | Matcher order | Admin rule before generic; test it |
| H2 console blank page | iframe refused | `X-Frame-Options: DENY` | Dev-only chain with `sameOrigin` |
| Swagger 401 | `/v3/api-docs` blocked | Not permitted | Add to permitAll |
| `LazyInitializationException` reports from teammates | 500 on GET | `open-in-view=false` + mapping outside the transaction | Teach: map inside `@Transactional` service methods |
| Flyway "Validate failed" on someone's laptop | App won't start | Edited a merged migration / branch switching | Delete `data/`; enforce "never edit merged migrations" |
| Migrations OK on H2, fail on Postgres (CI/RDS) | Red CI | Non-portable SQL | Handbook §8.1 list; CI on Postgres from W5 |
| `@DataJpaTest` ignores Postgres | Tests always on H2 | Default `replace = ANY` | Intended; integration tests (`@SpringBootTest`) cover Postgres |
| Merge conflicts in `pom.xml` / yml | Every week | Several editors | You are the sole editor |
| ECS task restarts forever | "Task failed ELB health checks" | No grace period / wrong health path / port | Grace 120s, `/actuator/health` public, target port 8080, SG ALB→ECS |
| ECS task can't pull the image | `CannotPullContainerError` | Public subnet without public IP / no route to ECR / execution role | `assign_public_ip = true`; execution role policies |
| ECS task can't read secrets | `ResourceInitializationError` | Execution role lacks `ssm:GetParameters` / `kms:Decrypt` | Fix the execution role (not the task role) |
| App can't reach RDS | Connection timeout | SG chain / wrong subnets / wrong JDBC URL | RDS SG ingress from ECS SG; `jdbc:postgresql://<endpoint>:5432/campus` |
| `exec format error` | Container exits instantly | arm64 image on x86 Fargate | Build amd64 in CI |
| 401 only through CloudFront | API works on the ALB directly | `Authorization` header not forwarded | Origin request/cache policy (see W7–8) |
| Refreshing `/reservations` page gives a 403/404 XML error | SPA deep link | S3 has no such key | CloudFront Function rewrite to `/index.html` |
| Old frontend after deploy | Cached `index.html` | CloudFront cache | Invalidate `/index.html`; Vite hashes the other files |
| Wrong times on AWS | Bookings off by 2–3h | Container is UTC; someone used `now()` | Shared `Clock`; grep for `.now()` in reviews |
| Reassignment runs twice in prod | Double notifications | Local listener + SQS consumer both active | `@Profile("!prod")` / `@Profile("prod")` |
| SQS message reprocessed / stuck | Repeated logs; DLQ fills | Exception before delete; visibility timeout too short | Idempotent handler; DLQ alarm; timeout > processing time |
| `@Scheduled` jobs run on 2 tasks | Double overdue notices | Multiple ECS tasks | Idempotent jobs (update only rows still in the old status); 1 task normally; ShedLock as a stretch |
| SES email not delivered | `MessageRejected` | Sandbox; unverified recipient | Verify recipients; log the failure, don't fail the request |
| Unexpected bill | Budget alert | NAT, idle ALB/RDS, forgotten snapshots/IPs | §5 guardrails; tags; destroy |
| RDS stopped → app down before demo | Startup failures | Auto-stop/start cycle, you stopped it | Start RDS 15 min before the demo; checklist |
| Secret committed | GitHub push protection alert | `.env`/tfvars added | Rotate, purge, add to `.gitignore` |
| You become the bottleneck | PRs waiting for your review / deps | Too many responsibilities | CODEOWNERS spreads reviews; batch dependencies; protect W1–W3 for platform work only |

---

## 8. Your personal checklist

- [ ] W1: repo + protection + skeleton + CI + README; all 4 laptops run it
- [ ] W2: contracts + entity freeze prepared; AWS account secured; budgets set
- [ ] W3: auth/JWT + security rules + tests; `common` (errors, Clock, paging, CurrentUser); stubs; seeders; Swagger Authorize
- [ ] W4–5: ArchUnit; audit; local storage; Terraform VPC/RDS/S3; Postgres in CI
- [ ] W6: Dockerfile; ECR
- [ ] W7–8: ECS + ALB + CloudFront + S3 hosting; OIDC deploy pipeline; S3StorageService; first live deploy
- [ ] W9: SQS bridge + consumer + DLQ; SES; alarms
- [ ] W10: security & load smoke tests; backup restore test; cost check
- [ ] W11–12: diagrams + chapters + runbook; demo + fallback; tear down after grading
- [ ] Throughout: reviews within 24h, unblock teammates, own tests ≥70% on your services

## 9. What you present at the defense
Architecture overview (modular monolith + AWS 3-tier), security design (JWT, RBAC, ownership checks), profile-based adapters (Local/S3, Log/SES, Spring events/SQS: Strategy/Adapter + Observer), CI/CD pipeline, cost/HA trade-offs (NAT vs. public subnets, single vs. Multi-AZ), the audit log, and your tests.

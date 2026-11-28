# Reusable Template Blueprint (Post-Defense)

**Author:** Matei Necula · **State of the codebase:** `main` at `545e334` (28 Nov 2026) · **Target timing:** After the `v1.0.0` FIS defense

This document maps how **Campus Equipment & Incident Hub** can be extracted into a domain-agnostic GitHub Template Repository for any organization that lends physical items or spaces by time slot, handles breakdowns, and automatically reassigns affected bookings.

---

## 1. Why extract after the defense (not before)

During the FIS semester, keeping the database schema, roles (`STUDENT` / `ADMIN`), endpoints, and UI labels anchored to university lab equipment is deliberate:

| Reason | Why we keep campus naming through `v1.0.0` |
|---|---|
| **Frozen schema & grading clarity** | All 12 PostgreSQL 18 tables, 18 Flyway migrations, 71 REST endpoints, and 20 React pages match the approved FIS requirements, report chapters, and `DemoFlowIT` defense script. |
| **Zero migration churn** | Renaming `lab_rooms`, `assets.model`, or `Role.STUDENT` mid-semester would force breaking Flyway migrations, frontend type updates, and test rewrites across all 4 teammates right before feature freeze. |
| **Clean seams already exist** | Because the codebase enforces strict `api`-only module boundaries (`28` `@ArchTest` rules), isolating the domain-specific parts after `v1.0.0` is a ~3–5 day refactor rather than a rewrite. |

---

## 2. Layer split: ~70% reusable core vs. 3 swappable domain seams

Roughly 70% of the engineering work in this repository—concurrency locking, idempotency, real-time SSE, fail-closed security, file validation, cloud IaC, and CI/CD—has nothing specific to university electronics labs.

### Already domain-agnostic (~70% of the system)

| Layer / Component | Location | What any booking + incident domain gets out of the box |
|---|---|---|
| **Shared foundations (`common/*`)** | `com.campus.common.{error,paging,audit,security,time}` | RFC 9457 `ProblemDetail` (`GlobalExceptionHandler`), `PageResponse<T>` / `PageRequests`, transactional `audit_logs` (`AuditService`), JWT `CurrentUser`, injected `Clock` (`app.time-zone`). |
| **Security & platform (`config/*`)** | `com.campus.config.*` | Fail-closed `SecurityConfig` (HS256 JWT + live scalar `findActiveRoleById` on every request), `SecurityErrorHandler`, `OpenApiConfig`, `AsyncConfig` (3-pool isolation: `campus-async-`, `campus-ses-`, `campus-sched-`), `SpaConfig` (single-origin React serving), `AwsConfig`. |
| **Identity, RBAC & pickup pass (`user/`)** | `com.campus.user.*` | BCrypt (with 72-byte UTF-8 check) + JWT auth, `AdminBootstrap`, `UserSeeder`, `AdminUserController` (enable/disable/role + `UserDisabledEvent`), and stateless 30-second HMAC-SHA256 rotating 6-digit/QR desk passes (`PickupCodes`, `PickupPassService`). |
| **Storage & notifications (`storage/`, `notification/`)** | `com.campus.{storage,notification}.*` | `@Profile("!prod")` / `@Profile("prod")` adapter pairs (`LocalStorageService` / `S3StorageService` with magic-byte + path-traversal validation; `LoggingNotificationService` / `SesNotificationService`), plus `LiveStreams` SSE registry (20 s `:hb` heartbeat, 5-stream/user cap, instant disconnect on disable). |
| **Concurrency, idempotency & calendar primitives** | `com.campus.reservation.service.{IdempotencyService,IcsWriter,CalendarTokenService}` | Strict PostgreSQL 18 lock-ordering DAG (`Quota -> Reservation -> Asset -> Transfer -> Hold`), `REQUIRES_NEW` `Idempotency-Key` replay/takeover protection, open-ended `OVERDUE` overlap SQL, 1-minute slot holds, and 256-bit secret-token `.ics` feeds. |
| **Reassignment & impact-simulation framework** | `ReassignmentService`, `ReassignmentBookingProcessor`, `MaintenanceImpactSimulator` | `AFTER_COMMIT` Observer listeners, per-booking `REQUIRES_NEW` isolation with transient PostgreSQL SQLState (`40P01`/`55P03`/`40001`) retry, `expectedSourceAssetId` double-move guard, and zero-write 90-day virtual impact simulation. |
| **Cloud IaC, container & CI/CD** | `infra/*.tf`, `Dockerfile`, `.github/workflows/*` | 3-stage CVE-patched non-root Alpine image, Terraform VPC + private RDS PG 18 + CloudFront prefix-list SG + IMDSv2 + SSM SecureString + SNS alerts, GitHub OIDC `deploy.yml` (zero static AWS keys), `ci.yml`, `docs-check.yml`, `sync-wiki.yml`. |
| **Architecture & quality gates** | `com.campus.architecture.*`, `build.gradle` | `28` ArchUnit `@ArchTest` rules (`ModuleBoundaryArchitectureTest` + `CodingConventionsArchitectureTest`), Spotless formatting, JaCoCo, and on-demand PIT mutation testing (`./gradlew pitest`). |

### The 3 swappable domain seams

| Seam | Current campus-specific implementation | What changes for a new domain |
|---|---|---|
| **Seam 1: Resource taxonomy & location (`catalog/`)** | `lab_rooms` table (required FK on `assets`), mandatory `assets.model`, `serial_number`, `categories`, and `CatalogSeeder`. | Generalize `lab_rooms` to optional `locations` (ward, studio, parking bay, building floor) and `assets.model` to an optional `substitute_group` key; replace `CatalogSeeder` with domain seed data. |
| **Seam 2: Booking windows & quotas (`reservation/`, `incident/`)** | Hard-coded constants in `ReservationSlotValidator` (`08:00–20:00`, Mon–Fri, `:00/:30` steps, 30 min–4 h, 14-day horizon), `ReservationService` / `HoldService` / `TransferService` (2 active sessions, 1–5 bundle cap, 60 s hold / 2 min cooldown, 3 transfers/24 h), and `IncidentService` (3 open reports) — duplicated in `frontend/src/components/reservation/bookingRules.ts` and `ProjectDetailsPage.tsx`. | Move all timing/quota constants into `@ConfigurationProperties("app.booking")` and publish them via `GET /api/v1/config` so backend validation and frontend pickers stay in sync automatically. |
| **Seam 3: Substitution strategies & optional kits (`reassignment/`, `project/`)** | `SameModelStrategy` → `SameCategoryStrategy` (preferring same `labRoomId`, then lowest ID) + optional leaf `project/` module (10 bilingual RO/EN electronics guides in `resources/projects/*.json` + atomic "Reserve all"). | Register custom `@Order` `SubstituteStrategy` beans (e.g., same clinical certification, same lens mount, same seating capacity) and toggle `project/` kits on or off via `app.projects.enabled`. |

---

## 3. Domain mapping across 4 concrete industries

The same modular monolith and concurrency engine map directly onto four other real-world booking-and-incident domains without changing the core transaction, locking, or security architecture:

| Concept | Campus Lab Gear *(current)* | Hospital Medical Devices | Film / AV Studio Gear | University Fleet / Vehicles | Coworking / Study Rooms |
|---|---|---|---|---|---|
| **Bookable resource (`assets`)** | Oscilloscope, multimeter, FPGA board | Infusion pump, portable ultrasound, ECG monitor | Cinema camera, prime lens, boom mic kit | Lab van, EV shuttle, field-trip minibus | Quiet pod, whiteboard room, recording booth |
| **Location (`lab_rooms` → `locations`)** | Lab room (`E-204`, `B-101`) | Ward / wing (`ICU-2`, `Cardiology`) | Equipment cage / stage (`Stage A`, `Vault B`) | Parking lot / campus gate (`North Lot`) | Building & floor (`Hub-3F`, `Library-2F`) |
| **Substitute group (`model` → `substitute_group`)** | Hardware model (`Rigol DS1054Z`) | Certified device class + firmware spec | Lens mount / camera body line (`FX6`, `RF-24-70`) | Vehicle class + license tier (`B-Manual-9Seat`) | Seat capacity + AV tier (`6-seat-4K`) |
| **Booking window & quota** | Mon–Fri `08:00–20:00`, 30 m–4 h, 14 d, max 2 | 24/7 shifts, 1 h–12 h, 7 d, role-based quota | Daily `07:00–23:00`, 2 h–48 h, 30 d, max 3 | Daily `06:00–22:00`, 1 h–24 h, 30 d, max 1 | Daily `07:00–22:00`, 30 m–4 h, 14 d, max 2 |
| **Outage / reassignment trigger** | Broken gear (`HIGH`/`CRITICAL` incident) or retire | Calibration failure, sterilization lock, fault report | Sensor/lens damage, missing battery pack | Check-engine fault, flat tire, inspection due | Projector/AC outage, facility maintenance |
| **Optional kits (`project/`)** | Bilingual lab project kits ("Reserve all") | Clinical procedure trays (disabled if unneeded) | Shoot packages ("Interview Kit", "Run & Gun") | Field-trip bundles (van + GPS + first-aid kit) | Event bundles (room + wireless mic + webcam) |

---

## 4. Concrete post-defense extraction checklist (~3–5 days)

After tagging `v1.0.0` and completing the FIS defense, convert the repository into a reusable GitHub Template Repository in seven small, verifiable PRs:

| # | Step | Exact files / targets | Effort |
|---|---|---|---:|
| **1** | **Externalize booking rules + config endpoint** | Add `@ConfigurationProperties("app.booking")` (`openTime`, `closeTime`, `allowedDays`, `slotStepMinutes`, `minDurationMinutes`, `maxDurationMinutes`, `maxAdvanceDays`, `maxActiveSessions`, `maxBundleSize`, `holdSeconds`, `holdCooldownSeconds`, `maxTransfersPer24h`, `maxOpenIncidents`). Expose public `GET /api/v1/config` (added to `SecurityConfig` `permitAll`) and delete duplicated constants in `frontend/src/components/reservation/bookingRules.ts` and `ProjectDetailsPage.tsx`. | ~1 day |
| **2** | **Centralize frontend branding & vocabulary** | Create `frontend/src/config/domainLabels.ts` (app name, role display names, resource/location nouns, optional kit tab label) and wire `AppLayout.tsx`, `navigation.ts`, `theme.ts`, and page titles to read from it instead of hard-coded `"Campus Hub"` / `"Lab room"` / `"Student"` strings. | ~0.5 day |
| **3** | **Generalize catalog taxonomy** | Add Flyway migration making `assets.lab_room_id` nullable (renaming or aliasing `lab_rooms` → `locations`) and making `assets.model` → nullable `substitute_group`; update `SameModelStrategy` → `SameSubstituteGroupStrategy` so it cleanly skips when `substitute_group` is blank. | ~0.5–1 day |
| **4** | **Generalize user role naming** | Rename `Role.STUDENT` → `Role.MEMBER` (keeping `Role.ADMIN`) via a Flyway data + constraint migration and update `SecurityConfig`, controllers, and frontend role guards. | ~0.5 day |
| **5** | **Make the `project/` kits module opt-in** | Annotate `ProjectLibrary`, `ProjectService`, `ProjectReservationService`, and `ProjectController` with `@ConditionalOnProperty(prefix = "app.projects", name = "enabled", havingValue = "true", matchIfMissing = true)` and include `projectsEnabled` in `GET /api/v1/config` so adopters without bundled kits toggle one flag. | ~0.25 day |
| **6** | **Add zero-install `docker-compose.yml`** | Add a root `docker-compose.yml` launching PostgreSQL 18 (`campus` + `campus_test` init script) so template users can run `docker compose up -d && cd backend && ./gradlew bootRun` without manual pgAdmin setup. | ~0.25 day |
| **7** | **Parameterize Terraform & CI/CD** | Replace literal `"campus-hub"`, `"/campus/"`, and the hardcoded `github_deploy_subject` across `infra/*.tf` (`variables.tf`, `network.tf`, `security_groups.tf`, `rds.tf`, `storage.tf`, `iam.tf`, `ssm.tf`, `compute.tf`, `cdn.tf`, `alerts.tf`, `github-deploy.tf`, `versions.tf`) and `.github/workflows/deploy.yml` with variables (`project_name`, `ssm_prefix`, `github_repo`, `github_oidc_sub`), then enable GitHub's **"Template repository"** setting. | ~0.5–1 day |

**Total estimated effort:** **3.5–4.5 developer-days** after `v1.0.0`, while keeping all `28` ArchUnit rules, the concurrency/locking test suite, `SecurityPassIT`, and GitHub OIDC deployment intact.

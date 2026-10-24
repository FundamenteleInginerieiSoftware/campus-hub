# Decisions

Every team decision, with its date. Newest first inside each section. If something here changes, add a new line with the new date instead of rewriting history, and mention the PR.
Where the plan PDFs (team handbook + member guides) disagree with this file, **this file wins**.

**Status:** ✅ decided · 🟡 proposed, confirm at the next meeting · ⏳ open

---

## 1. Team and process

| Date | Decision | Status | Notes |
|---|---|---|---|
| 2026-10-21 | **Role split v2** (final) | ✅ | Matei: auth/security, build files, all AWS, admin reservations desk page. Liviu: catalog + shared services (errors, paging, audit, storage/notification interfaces + local versions). Calin: reservations + reassignment engine, `Clock`, ArchUnit. Stefania: incidents, user admin + `CurrentUser`, frontend lead, CI. Full table in `AGENTS.md` §1 |
| 2026-10-21 | Each member builds the frontend pages of their own module | ✅ | Following Stefania's conventions; she reviews every frontend PR |
| 2026-10-08 | Review buddies: **Matei ↔ Liviu**, **Calin ↔ Stefania** | ✅ | `.github/CODEOWNERS` requests owner + buddy automatically |
| 2026-10-08 | GitHub Flow: branch per issue, PR + 1 approval + green `ci`, **squash merge**, Conventional Commits | ✅ | Ruleset `protect-main` |
| 2026-10-08 | All files removed from `main` (#10) and the project restarted | ✅ | Restart PRs: #12 (repo guides), #13 (Gradle skeleton), #14 (Matei's docs) |
| – | Weekly meeting day/time, chat channel | ⏳ | Fill in at the next meeting |

## 2. Stack and build

| Date | Decision | Status | Notes |
|---|---|---|---|
| 2026-10-21 | **Java 25 + Gradle** (Groovy DSL, wrapper) | ✅ | Replaces Maven + Java 21 from the plan. Follows Liviu's #11. Spring Boot 4.1 supports Java 17–25; the foojay plugin downloads JDK 25 if it's missing |
| 2026-10-21 | Backend lives in **`backend/`**, base package **`com.campus`** | ✅ | Liviu's #11 (repo root, `com.campus.incident_hub`) moves into it |
| 2026-10-21 | Spring Boot **4.1.1**, springdoc **3.1.1**, Spotless (palantir-java-format **2.102.0**) checked by `build`, JaCoCo report after tests | ✅ | Only Matei edits `build.gradle` / `settings.gradle` / `gradle/`; request libraries with a `dependency` issue |
| 2026-10-21 | Settings in **YAML** (`application.yml`, `-dev`, `-prod`, test `-test`) | ✅ | One file per profile, `.yml` extension only |
| 2026-10-06 | Frontend: React + Vite + TypeScript, Node 24 LTS | ✅ | Stefania picks the UI kit (Mantine recommended) |

## 3. Database

| Date | Decision | Status | Notes |
|---|---|---|---|
| 2026-10-21 | **Local PostgreSQL 18 + pgAdmin** on every laptop; no H2 | ✅ | Considered: H2, Docker Compose + Testcontainers, embedded Postgres (zonky), hosted DB per person, RDS per developer, one shared DB. Local install = easiest first day, and real Postgres. Setup: README §1.1 |
| 2026-10-21 | Same version everywhere: **PostgreSQL 18** (laptops, CI service container, RDS) | ✅ | RDS eu-central-1 offers 18.x |
| 2026-10-21 | Dev login `campus` / `campus`, databases `campus` (app) and `campus_test` (tests) | ✅ | Dev-only, fine to commit. Override with `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`; never commit personal passwords |
| 2026-10-08 | Tables only via **Flyway**, Hibernate `ddl-auto: validate` | ✅ | |
| 2026-10-08 | Migration names: `V<YYYY_MM_DD_HHMM>__<module>_<desc>.sql` | ✅ | Rebase before merging; if `main` has a later timestamp, rename yours to the current time |
| – | Entity freeze session (all entities + migrations in one PR) | ⏳ | After the shared foundations are on `main` (see §6) |

## 4. Architecture

| Date | Decision | Status | Notes |
|---|---|---|---|
| 2026-10-23 | **AWS: one EC2 instance (Docker) behind CloudFront + RDS PostgreSQL 18**; no ECS, ALB or NAT gateway | ✅ | [ADR-002](adr/002-single-ec2-behind-cloudfront.md), diagram `docs/diagrams/aws-architecture.drawio`. Replaces ECS Fargate + ALB from the plan PDFs. ~$35/month at most, paid by the credits |
| 2026-10-23 | In prod, the Spring Boot app also serves the React build (one Docker image, one origin) | 🟡 | ADR-002. Stefania: please confirm. Dev is unchanged (Vite proxy); Matei's Dockerfile runs `npm ci && npm run build` and copies `frontend/dist` into the image |
| 2026-10-23 | Prod secrets in **SSM Parameter Store** `SecureString` (`/campus/*`), not Secrets Manager | ✅ | [ADR-003](adr/003-ssm-parameter-store-for-secrets.md). Free; no rotation (documented limitation) |
| 2026-10-23 | **Local-first:** everyone works locally; AWS only for deploys (W7–W9) and the demo, all through Terraform | ✅ | [ADR-001](adr/001-local-first.md) |
| 2026-10-23 | AWS account stays on the **Free plan** ($100 credits until 17 Mar 2027), no AWS Organizations / Identity Center; budget alerts $5 / $20 / $40 | ✅ | [ADR-005](adr/005-aws-account-free-plan.md). Only Matei has AWS access |
| 2026-10-21 | **SQS is a stretch goal**, behind `app.messaging.sqs.enabled` (default `false`) | ✅ | `false`: Calin's local `@TransactionalEventListener(AFTER_COMMIT)` runs the engine, also in prod. `true`: Matei's SQS bridge. Never both |
| 2026-10-06 | Modular monolith: modules talk only through their `api` package or events | ✅ | Enforced by Calin's ArchUnit rules (freeze mode from W4, strict after M1) |
| 2026-10-06 | Asset status is operational only: `AVAILABLE`, `UNDER_MAINTENANCE`, `RETIRED` | ✅ | "Free at a time" is computed from reservations |
| 2026-10-06 | Assets are never hard-deleted, only retired | ✅ | |
| – | A reassigned reservation stays `CONFIRMED` (+ `original_asset_id`, `reassignment_log`) instead of a new status | 🟡 | Recommended in the plan; confirm |
| – | Time types: `LocalDateTime` = campus local time (Europe/Bucharest) for reservation slots, `Instant` for audit/event timestamps | 🟡 | Confirm |

## 5. Business rules (numbers)

All 🟡: proposed in the plan, **confirm at the next meeting** (Calin leads).

| Rule | Proposed value | Owner |
|---|---|---|
| Max active reservations per student | 2 | Calin |
| Max booking horizon | 14 days ahead | Calin |
| Min / max reservation length | 30 min / 4 h | Calin |
| Slot granularity | start/end on :00 or :30 | Calin |
| Lab opening hours | Mon–Fri 08:00–20:00 | Calin |
| Student cancellation | until start time | Calin |
| Checkout window | 15 min before start → 30 min after start | Calin |
| No-show | not checked out 30 min after start → `NO_SHOW` | Calin |
| Admins | bypass the per-student limit, not the conflict check | Calin |
| Photo | optional, max 5 MB, JPEG/PNG/WebP | Stefania |
| Substitute search | same model → same category (same lab room preferred) | Calin |
| Reassignment scope | all future active reservations of the asset | Calin |
| User admin | admins can't disable/demote themselves; the last admin can't be removed | Stefania |
| JWT lifetime | 8 h, no refresh tokens (documented limitation) | Matei |

## 6. Schedule

| Date | Decision | Status | Notes |
|---|---|---|---|
| 2026-10-21 | W1 = week of **5 Oct 2026**; we are in **W3** and about one week behind (restart) | 🟡 | Confirm real dates and the defense date; W11–12 hit the Christmas holidays |
| 2026-10-21 | Shared foundations first: Calin's `Clock` (early W3), Liviu's exceptions + handler + `PageResponse` + stubs (mid-W3), Stefania's `CurrentUser` (mid-W3), Matei's login/JWT (end of W3) | ✅ | Small separate PRs, reviewed the same day |
| – | Milestones: M1 end of W6 (`v0.1.0`), M2 end of W9 (`v0.2.0`), feature freeze start of W10 | 🟡 | Move by one week if needed after the restart |

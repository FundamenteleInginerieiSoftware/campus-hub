# Decisions

Every team decision, with its date. Newest first inside each section. If something here changes, add a new line with the new date instead of rewriting history, and mention the PR.
Where the plan PDFs (team handbook + member guides) disagree with this file, **this file wins**.

**Status:** ✅ decided · 🟡 proposed, confirm at the next meeting · ⏳ open · replaced = a newer dated row above takes over

---

## 1. Team and process

| Date | Decision | Status | Notes |
|---|---|---|---|
| 2026-11-20 | **Open decisions are settled by Matei** for the team: the proposed and open rows and which off-plan features stay | ✅ | Agreed by the team; this PR settles the rows that were still open. Weekly meeting day/time stays open until Matei fills it in |
| 2026-10-21 | **Role split v2** (final) | ✅ | Matei: auth/security, build files, all AWS, admin reservations desk page. Liviu: catalog + shared services (errors, paging, audit, storage/notification interfaces + local versions). Calin: reservations + reassignment engine, `Clock`, ArchUnit. Stefania: incidents, user admin + `CurrentUser`, frontend lead, CI. Full table in `AGENTS.md` §1 |
| 2026-10-21 | Each member builds the frontend pages of their own module | ✅ | Following Stefania's conventions; she reviews every frontend PR |
| 2026-11-04 | **No more review buddies: anyone can review anyone's PR** | ✅ | Matei. 1 approval from any teammate is enough (ruleset: code owner review off); `.github/CODEOWNERS` requests all four of us on every PR. The 24h rule stays: the first one free picks it up. Code ownership (who writes what) is unchanged |
| 2026-10-08 | ~~Review buddies: Matei ↔ Liviu, Calin ↔ Stefania~~ | ✅ | Replaced on 2026-11-04 (row above) |
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
| 2026-11-20 | **No separate entity freeze session**: the tables landed module by module (last frozen table `reassignment_log`, #37). Freeze rule since M1: frozen fields/columns are not renamed or removed without a team vote; adding is fine | ✅ | decided by Matei for the team (the team asked him to settle the open rows). Replaces the open row below |
| 2026-10-21 | **Local PostgreSQL 18 + pgAdmin** on every laptop; no H2 | ✅ | Considered: H2, Docker Compose + Testcontainers, embedded Postgres (zonky), hosted DB per person, RDS per developer, one shared DB. Local install = easiest first day, and real Postgres. Setup: README §1.1 |
| 2026-10-21 | Same version everywhere: **PostgreSQL 18** (laptops, CI service container, RDS) | ✅ | RDS eu-central-1 offers 18.x |
| 2026-10-21 | Dev login `campus` / `campus`, databases `campus` (app) and `campus_test` (tests) | ✅ | Dev-only, fine to commit. Override with `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`; never commit personal passwords |
| 2026-10-08 | Tables only via **Flyway**, Hibernate `ddl-auto: validate` | ✅ | |
| 2026-10-08 | Migration names: `V<YYYY_MM_DD_HHMM>__<module>_<desc>.sql` | ✅ | Rebase before merging; if `main` has a later timestamp, rename yours to the current time |
| – | Entity freeze session (all entities + migrations in one PR) | replaced | After the shared foundations are on `main` (see §6) |

## 4. Architecture

| Date | Decision | Status | Notes |
|---|---|---|---|
| 2026-11-20 | **Calin's off-plan features, narrowed** (replaces the "back on" row below): **kept**, in this order: 1. FIFO waitlist with promotion on freed slots (#59) · 2. small maintenance impact simulator, dry run of "what if this asset goes down", reusing the engine's strategies (#58, one endpoint + one panel) · 3. "next free slots" for an asset (#61, the recommender part only). **Dropped**: demand heatmap / analytics (#255, #256: Stefania's reliability page #291 covers admin analytics), global optimal planner (#258: single-asset bookings never overlap, so the greedy engine is already optimal), chain-shifting resolver (#260: extra risk in the most demo-critical code), retry with backoff (#265: locks are taken in a fixed order and #270 already retries on concurrency conflicts) | ✅ | Decided by Matei for the team, after a closer look (the drop reasons are Calin's own closing comments on those PRs). Same rules: small PRs (≤ ~400 lines) with tests + personal doc, first PR of each by **30 Nov**, all by the **feature freeze 7 Dec**, otherwise cut; 2 and 3 only after 1 and Calin's #311 / #319. No overlap with #286 |
| 2026-11-20 | **Retired asset: its future bookings are reassigned by Calin's engine** (same Strategy order as for damage), force-cancel when no substitute is free; `reassignment_log.incident_id` nullable + `trigger` (`INCIDENT`/`RETIRED`) | ✅ | decided by Matei for the team (the team asked him to settle the open rows). Replaces the proposed row below; Liviu's `AssetRetiredEvent` (#280), Calin's listener #311 (Closes #245). Double move when an incident and a retire hit the same asset: Calin, #319 |
| 2026-11-20 | **Time types confirmed**: `LocalDateTime` (campus time, Europe/Bucharest) in entities, columns and JSON; `Instant` only in event payloads (`AssetDamagedEvent.occurredAt`) | ✅ | decided by Matei for the team (the team asked him to settle the open rows). Replaces the proposed row below; matches the code on `main` |
| 2026-11-20 | **ArchUnit strict mode now** (M1 is done): no frozen violations left, new ones fail the build | ✅ | decided by Matei for the team (the team asked him to settle the open rows). Calin, small PR by 30 Nov |
| 2026-11-20 | **Calin's off-plan features are back on**, rebuilt on current `main` as small PRs (≤ ~400 lines, normal review, tests + personal doc each), in this order: 1. FIFO waitlist with promotion on freed slots (#59) · 2. next-free-slot recommender (#61; substitute ranking only where it adds to #286) · 3. maintenance impact simulator, dry run of "what if this asset goes down" (#58) · 4. demand heatmap / utilization analytics, one feature (#255 + #256) · 5. retry with backoff in the engine (#265, extends #270) · 6. global reassignment planner (#258) · 7. chain-shifting resolver (#260) | replaced | decided by Matei for the team (the team asked him to settle the open rows). Rules: each feature's first PR on `main` by **30 Nov**, all done by the **feature freeze 7 Dec**, otherwise cut. 6 and 7 behind a config flag, default off, so the current engine stays the default and `DemoFlowIT` stays green. No duplicates of merged work (#286, #270) or of Stefania's reliability page (#291): 4 coordinates with her. Idempotency (#262) stays |
| 2026-10-23 | **AWS: one EC2 instance (Docker) behind CloudFront + RDS PostgreSQL 18**; no ECS, ALB or NAT gateway | ✅ | [ADR-002](adr/002-single-ec2-behind-cloudfront.md), diagram `docs/diagrams/aws-architecture.drawio`. Replaces ECS Fargate + ALB from the plan PDFs. ~$35/month at most, paid by the credits |
| 2026-10-23 | In prod, the Spring Boot app also serves the React build (one Docker image, one origin) | ✅ | ADR-002, confirmed by Stefania on 2026-11-04 (issue #105). Dev is unchanged (Vite proxy); Matei's Dockerfile runs `npm ci && npm run build` and copies `frontend/dist` into the image |
| 2026-10-23 | Prod secrets in **SSM Parameter Store** `SecureString` (`/campus/*`), not Secrets Manager | ✅ | [ADR-003](adr/003-ssm-parameter-store-for-secrets.md). Free; no rotation (documented limitation) |
| 2026-10-23 | **Local-first:** everyone works locally; AWS only for deploys (W7–W9) and the demo, all through Terraform | ✅ | [ADR-001](adr/001-local-first.md) |
| 2026-10-23 | AWS account stays on the **Free plan** ($100 credits until 17 Mar 2027), no AWS Organizations / Identity Center; budget alerts $5 / $20 / $40 | ✅ | [ADR-005](adr/005-aws-account-free-plan.md). Only Matei has AWS access |
| 2026-10-21 | Row timestamps (`created_at`, …): **`LocalDateTime` + `TIMESTAMP`**, campus local time from `LocalDateTime.now(clock)` | ✅ | Replaces the `Instant` idea below for table columns: one time type in entities and JSON, and `ddl-auto: validate` would reject an `Instant` field on a plain `TIMESTAMP` column. First used by `users.created_at`. Liviu: same for the audit table |
| 2026-10-21 | **SQS is a stretch goal**, behind `app.messaging.sqs.enabled` (default `false`) | ✅ | `false`: Calin's local `@TransactionalEventListener(AFTER_COMMIT)` runs the engine, also in prod. `true`: Matei's SQS bridge. Never both |
| 2026-10-06 | Modular monolith: modules talk only through their `api` package or events | ✅ | Enforced by Calin's ArchUnit rules (freeze mode from W4, strict after M1) |
| 2026-10-06 | Asset status is operational only: `AVAILABLE`, `UNDER_MAINTENANCE`, `RETIRED` | ✅ | "Free at a time" is computed from reservations |
| 2026-10-06 | Assets are never hard-deleted, only retired | ✅ | |
| 2026-11-07 | A reassigned reservation stays `CONFIRMED` (+ `original_asset_id`, `reassignment_log`) instead of a new status | ✅ | Implemented by Calin; moves are audited in `reassignment_log` and flagged in the UI |
| – | Retired asset future bookings: automatically reassigned using Calin's engine (Strategy pattern), with fallback to force-cancel | replaced | Agreed by Liviu & Calin in #245 (`AssetRetiredEvent` + nullable `reassignment_log.incident_id`); awaiting Stefania/Matei confirmation at next meeting |
| – | Time types: `LocalDateTime` = campus local time (Europe/Bucharest) for reservation slots, `Instant` for audit/event timestamps | replaced | Superseded for table columns on 2026-10-21 (row above). `Instant` stays only in event payloads (`AssetDamagedEvent.occurredAt`): confirm |

## 5. Business rules (numbers)

**2026-11-20: all values below are ✅ confirmed** (decided by Matei for the team), and every one was checked against the code on `main` (`328dec4`): all match. Changing a number from now on is a new dated line here plus a PR.

| Rule | Proposed value | Owner |
|---|---|---|
| Max active reservations per student | 2 | Calin |
| Multi-asset bundle quota | Bundle counts as 1 lab session slot towards the max 2 limit (#140, #228) | Calin |
| Max booking horizon | 14 days ahead | Calin |
| Min / max reservation length | 30 min / 4 h | Calin |
| Slot granularity | start/end on :00 or :30 | Calin |
| Lab opening hours | Mon–Fri 08:00–20:00 | Calin |
| Student cancellation | until start time | Calin |
| Checkout window | 15 min before start → 30 min after start | Calin |
| No-show | not checked out 30 min after start → `NO_SHOW` | Calin |
| Admins | bypass the per-student limit, not the conflict check | Calin |
| Photo | optional, max 5 MB, JPEG/PNG/WebP | Stefania |
| Incident severity (#229) | only `HIGH`/`CRITICAL` take the asset out of use at once and start the reassignment engine; `LOW`/`MEDIUM` are tickets, the asset goes out of use only when an admin starts the repair. Agreed by Matei and Calin in #229 | Stefania |
| Open reports per student (#229) | max 3 `OPEN` at a time (409 `TOO_MANY_OPEN_INCIDENTS`); admins have no limit | Stefania |
| Substitute search | same model → same category (same lab room preferred) | Calin |
| Reassignment scope | all future active reservations of the asset | Calin |
| User admin | admins can't disable/demote themselves; the last admin can't be removed | Stefania |
| JWT lifetime | 8 h, no refresh tokens (documented limitation) | Matei |

## 6. Schedule

| Date | Decision | Status | Notes |
|---|---|---|---|
| 2026-11-22 | **Teacher feedback demo moved**: Mon 23 Nov → **Mon 7 Dec** (same day as feature freeze, right after M2 `v0.2.0` on Sun 6 Dec) | ✅ | decided by Matei; updates the demo date in the 20 Nov row below |
| 2026-11-20 | **Dates**: W1 = week of 5 Oct 2026 · M1 `v0.1.0` done 11 Nov · teacher feedback demo Mon 23 Nov · off-plan/new-feature cut 30 Nov · M2 `v0.2.0` Sun 6 Dec (local + AWS) · feature freeze Mon 7 Dec | ✅ | decided by Matei for the team (the team asked him to settle the open rows). Replaces the two proposed rows below. Defense date: still open, it comes from the professor |
| 2026-10-21 | W1 = week of **5 Oct 2026**; we are in **W3** and about one week behind (restart) | replaced | Confirm real dates and the defense date; W11–12 hit the Christmas holidays |
| 2026-10-21 | Shared foundations first: Calin's `Clock` (early W3), Liviu's exceptions + handler + `PageResponse` + stubs (mid-W3), Stefania's `CurrentUser` (mid-W3), Matei's login/JWT (end of W3) | ✅ | Small separate PRs, reviewed the same day |
| – | Milestones: M1 end of W6 (`v0.1.0`), M2 end of W9 (`v0.2.0`), feature freeze start of W10 | replaced | Move by one week if needed after the restart |
| 2026-11-16 | **Four new features, one per member**: pickup pass (Matei), calendar feed (Calin), live notifications (Liviu), equipment reliability (Stefania) | ✅ | Specs + plans in `docs/superpowers/`; issues #289, #290, #291. Done before the feature freeze (7 Dec); cut lines per feature if a teammate's part is not on `main` by 30 Nov (`docs/whats-left.md` §7a) |

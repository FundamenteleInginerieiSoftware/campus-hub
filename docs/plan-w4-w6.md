# Plan W4–W6 (26 Oct – 15 Nov 2026)

The next three weeks for the whole team, ending with **M1 = `v0.1.0`** on 15 Nov. Based on the week plan (`AGENTS.md` §8) and each member's guide, adjusted to what is really on `main` today. Written 26 Oct 2026 (Monday, start of W4) by Matei. If something here is wrong for your module, fix it in a small `docs/` PR.

---

## 1. Where we are (updated 4 Nov 2026, W5)

> **What is left for each member, until the defense, is now in [`whats-left.md`](whats-left.md).** This plan stays as the detailed W4–W6 guide; the 26 Oct snapshot that was here is in the git history.

| Week | Result |
|---|---|
| **W4** | ✅ Done (Terraform part 1 was carried into W5 and merged as #249). Merged: #34–#37, #39, #40 (reservations, booking, incidents, reassignment engine, docs), #53, #62, #64, #66, #74, #76, #78, #80, #81, #82, #60, and Stefania's frontend #84–#86 + Calin's #87. **Prototype demo runs on `main`** (tag `v0.1.0-prototype` set) |
| **W5** | ✅ Done: #88, #89, #90, #92, photo upload, triage, user admin, report form. Terraform parts 1 + 2 landed in W6 (#249, #250) |
| **W6** | M1 still needs: asset search (#92), `CatalogSeeder`, catalog / asset details / inventory pages (Liviu), booking on asset details (Calin), register page (Stefania), Dockerfile (Matei, waits on ADR-002) |

**Tables on `main`:** `users`, `categories`, `lab_rooms`, `assets`, `reservations`, `incident_reports`, `reassignment_log`, `audit_logs`. **Dev accounts:** `student1@campus.test` (id 1), `student2@campus.test` (id 2), `admin@campus.test` (id 3), all with password `password`.

### Still to decide (🟡 / ⏳ in `docs/decisions.md`)

Weekly meeting time · real week dates and the defense date · milestones (M1 end of W6, M2 end of W9) · in prod, Spring serves the React build (**Stefania**: please confirm, it blocks the Dockerfile) · `Instant` only in event payloads · a reassigned reservation stays `CONFIRMED` · entity freeze row. **Calin** leads the next meeting on these.

---

## 2. Milestones

| When | Milestone | What must work |
|---|---|---|
| ✅ W4 | **Prototype demo** (runs on `main`; tag `v0.1.0-prototype` still to set) | In Swagger, on a fresh database: login → book → same slot again gives `409 RESERVATION_CONFLICT` → report an incident on the asset → the asset is `UNDER_MAINTENANCE` → booking it gives `409 ASSET_NOT_BOOKABLE` |
| **15 Nov (end of W6)** | **M1**, tag `v0.1.0` | Locally: a student registers, logs in, browses, books (conflicts rejected) and sees "My reservations"; an admin adds assets; CI green; every endpoint in Swagger |

---

## 3. Week by week

### W4 (26 Oct – 1 Nov): merge the open PRs, frontend scaffold, admin CRUD: ✅ done (Terraform part 1: ✅ #249)

| Who | Tasks |
|---|---|
| **Everyone** | Get the open PRs merged in the order above; **reviews the same day** while they're blocking others. Pull `main` and check the dev accounts work in Swagger (README section 2) |
| **Matei** | `archunit-junit5` in `build.gradle` (Mon) · merge #53, then `GET /api/v1/users/me` · prototype demo + tag · **Terraform part 1** (✅ #249): state in S3, network (VPC, 1 public subnet, 2 private DB subnets, no NAT), security groups (EC2 only from CloudFront, RDS only from EC2); `terraform plan` only |
| **Liviu** | **Admin CRUD** for assets, categories and lab rooms under `/api/v1/admin/**` (`SERIAL_NUMBER_TAKEN`, `CATEGORY_IN_USE`, ...), with retire instead of delete · **real `AuditService`**, recording admin actions · tests: service + `@WebMvcTest` (201, 400, 403 for a student, 404, 409) |
| **Calin** | Rebase #35 → #37 onto `main` one by one as each lower PR merges (drop the copies in #35) · `gh pr edit 39 --base main` · **ArchUnit module rules** (freeze mode) once the dependency is in |
| **Stefania** | Finish #40 (rebase, `CurrentUser` agreement, contract entries) · then a small follow-up: `markUnderMaintenance` on report (`CatalogApi` is on `main` now) · **frontend scaffold**: Vite + React + TS, `package.json`, API client that sends the token, login page, layout + routing by role, shared components (table with paging, error alert). The frontend CI job switches on by itself once `frontend/package.json` exists. *(CI on PostgreSQL + JaCoCo is already done.)* |

### W5 (2–8 Nov): search, booking rules, photos, AWS part 2

| Who | Tasks |
|---|---|
| **Matei** | **Terraform part 2** (✅ #250; RDS off until W7): RDS PostgreSQL 18 (`db.t4g.micro`, private, encrypted), S3 photos bucket, ECR, EC2 instance role, SSM parameters `/campus/*`; first `apply`, stop RDS when idle · **first admin in prod** from `ADMIN_EMAIL` / `ADMIN_PASSWORD` · security review of `LocalStorageService` (the S3 version must match it) · reviews |
| **Liviu** | **Asset search** with optional filters (`Specification`), `@EntityGraph` against N+1 queries, sort whitelist, `PageResponse` · `@DataJpaTest` for the filter combinations |
| **Calin** | The remaining booking rules from the handbook, each with a test · `GET /api/v1/reservations/me` · availability endpoint · real `ReservationApi` (whatever #36 doesn't cover yet) |
| **Stefania** | Photo upload + `GET /incidents/{id}/photo` (`StorageService`, ownership check) · admin triage: resolve/reject → `markAvailable` only if no other open incident on the asset · **user admin endpoints** in `user/admin/` · report-incident form (frontend) |
| **Team meeting** | Settle the 🟡 rows in `docs/decisions.md` (Calin leads) |

### W6 (9–15 Nov): pages + M1 `v0.1.0`

| Who | Tasks |
|---|---|
| **Matei** | **`backend/Dockerfile`** (two stages, Gradle + JDK 25, non-root) · run it locally with the `prod` profile · push to ECR · check every endpoint has a Swagger summary · **run the M1 flow mid-week** on a fresh database · tag `v0.1.0` · status update · React basics (admin desk page in W9) |
| **Liviu** | `CatalogSeeder` (`@Profile("dev") @Order(2)`; several assets of the **same model**, so the reassignment demo works) · pages: catalog, asset details, admin inventory · `docs/modules/common.md` |
| **Calin** | Admin check-out / return (time window → `CHECKOUT_WINDOW_CLOSED`) · admin reservation list with filters · `ReservationSeeder` data · pages: booking, My reservations |
| **Stefania** | Login / register pages · wire all M1 pages together · **run the full M1 flow with the team** · workload retro: if the frontend is behind, re-split now |

---

## 4. How we keep it moving

| Rule | Why |
|---|---|
| Review within 24h, same day if it blocks someone | Most of our delay so far was PRs waiting |
| Rebase onto `main` daily; stacked PRs move their base to `main` as the lower one merges | Strict "up to date" rule on `main` |
| Small PRs (< 400 lines), one doc per PR | Easier reviews, and your report writes itself |
| After pulling `main`: if Flyway complains or the dev accounts are missing, reset the local DB (README section 2) | Seeders only fill empty tables |
| Stuck for more than 2 hours: ask in the chat | AGENTS.md §9 |

## 5. Risks

| Risk | What we do |
|---|---|
| The open PRs (#34–#40) slip | Prototype demo and M1 slip with them: merge them first, early in W4 |
| No frontend by the end of W4 | Nobody can build pages in W6: Stefania starts the scaffold this week; if it's too much, we split it at the next meeting |
| `CurrentUser` not agreed | Blocks #35 and #40: agree on #40 by Tuesday |
| AWS costs | Budget alerts at $5 / $20; stop RDS and EC2 when idle (ADR-001) |

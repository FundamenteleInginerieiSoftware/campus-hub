# What's left: per member, until the defense

**As of Wed 4 Nov 2026, evening (W5).** This file is the one place for **what is still to do**. What is already done is in `README.md` §7 (per member) and `AGENTS.md` §7. The detailed plan for W5–W6 is `docs/plan-w4-w6.md`.

**Keep it current:** when your PR finishes a row, delete the row in the same PR (or tick it in the next status update). If a row is wrong for your module, fix it in a small `docs/` PR.

---

## 1. Milestones

| When | Milestone | Status |
|---|---|---|
| W4 | Prototype demo in Swagger | ✅ runs on `main` since 31 Oct (tag `v0.1.0-prototype` not set yet) |
| **Sun 15 Nov (end of W6)** | **M1 `v0.1.0`**: locally, a student registers, logs in, browses, books (conflicts rejected), sees My reservations; an admin adds assets; CI green; Swagger complete | Backend almost there; **frontend pages missing** (section 2) |
| **Sun 6 Dec (end of W9)** | **M2 `v0.2.0`**: everything locally **and on AWS**, incl. incident with photo → automatic reassignment → notification | Not started on AWS |
| Mon 7 Dec (W10) | **Feature freeze**: tests and bugs only | |
| W11–W12 (14–27 Dec) | Docs, diagrams, report, demo rehearsals, defense `v1.0.0` | Christmas break: finish before it. Real defense date still 🟡 |

## 2. What M1 still needs (by 15 Nov)

| Missing | Owner | Waits on |
|---|---|---|
| `CatalogSeeder` with several assets of the same model (demo + reassignment) | Liviu | – |
| Catalog page, asset details page, admin inventory page | Liviu | – |
| Booking on the asset details page (calendar component exists, #87) | Calin | Liviu's asset details page |
| Register page | Stefania | #107 (approved) |
| Run the full M1 flow on a fresh database, tag `v0.1.0` | Matei (with Stefania) | everything above |

## 3. Open PRs right now

| PR | Owner | What | Next step |
|---|---|---|---|
| #107 | Stefania | Register page | Approved: merge |
| #108 | Stefania | Incident start-repair, reject, my incidents, incident details | Approved: merge |
| #110 | Liviu | `ConstraintViolationException` / `HandlerMethodValidationException` → 400 `VALIDATION_FAILED` (closes #103) | Approved by Matei: merge |
| #58, #59, #61 | Calin | Simulator, waitlist, recommender (drafts) | Parked: off-plan, look again only after M1 |

---

## 4. Matei Necula: cloud, security, auth

| When | Task | Waits on |
|---|---|---|
| **W5 (now)** | **Terraform part 1** (`infra/`): S3 state bucket, VPC, 1 public subnet + 2 private DB subnets, no NAT, security groups (EC2 only from CloudFront, RDS only from EC2); `terraform plan` | – |
| W5 | **Terraform part 2**: RDS PostgreSQL 18 (`db.t4g.micro`, private, encrypted), S3 photos bucket, ECR, EC2 instance role, SSM parameters `/campus/*`; first `apply`, stop RDS when idle | Part 1 |
| W6 | **`backend/Dockerfile`** (2 stages, JDK 25, non-root, builds the React app into the image) · run it locally with `prod` · push to ECR | **Stefania confirms 🟡 ADR-002** |
| W6 | Check every endpoint has a Swagger summary · run the M1 flow mid-week · tag `v0.1.0` · status update | Section 2 |
| W7–W8 | **First AWS deploy**: EC2 (Docker) + CloudFront in front (ADR-002); `Authorization` header must reach Spring through CloudFront | Dockerfile, Terraform |
| W8 | **`deploy.yml`** with GitHub OIDC (no AWS keys in GitHub) · **`S3StorageService`** (`@Profile("prod")`, same key format and checks as Liviu's local one, #90 on `main`) · first live deploy **by the end of W8** | – |
| W9 | **`SesNotificationService`** (sandbox, verified team emails, send after commit, never roll back) · all prod secrets in SSM · M2 flow on AWS with Calin + Stefania · tag `v0.2.0` | – |
| W9 | Frontend: **admin reservations desk page** (today's pickups/returns, check-out/return, 409 messages), Stefania reviews | Calin's `src/api/reservations.ts` (on `main`) |
| W9 (stretch) | SQS bridge + consumer + DLQ, only if M2 is done and there is time | – |
| W10 | Security pass (IDOR with two student tokens, students rejected on admin endpoints, no stack traces) · load smoke test · restore an RDS snapshot once · cost check | – |
| W11–W12 | Deployment + component diagrams · security chapter (JWT sequence, RBAC table, threats) · CI/CD description · runbook · demo on AWS + local fallback + video · `terraform destroy` after grading | – |

## 5. Liviu Nedelcu: catalog + shared services

| When | Task | Waits on |
|---|---|---|
| W6 | **`CatalogSeeder`** (`@Profile("dev") @Order(2)`): ~4 categories, 3 lab rooms, 15–20 assets, **several units of the same model**. Agree with Calin (his seeded bookings) and Stefania (demo script) | – |
| W6 | Frontend: `src/api/catalog.ts` + **catalog page** (filters, paging, `UNDER_MAINTENANCE` greyed out) + **asset details** (slot for Calin's booking and Stefania's "report incident" button) + **admin inventory** (CRUD forms, 409 messages) | – |
| W6 | `docs/modules/common.md` (how to throw errors, page, audit; storage/notification profiles) | – |
| W7 | **Retire flow**: agree with Calin + Stefania what happens to a retired asset's future bookings (recommended: `AssetRetiredEvent`, handled by Calin's engine) | Team decision |
| W7–W8 | `STALE_VERSION` test for asset updates · **audit log endpoint** `GET /api/v1/admin/audit-log` + **audit log page** | – |
| W8 | Help Calin with substitute-query edge cases (none, several, retired excluded) | – |
| W10–W12 | Coverage ≥ 70% (`catalog.service`, `common.audit`, `storage`, handler) · final class, ER and Asset state diagrams · report section · demo part (admin adds an asset, student filters, audit log) | – |

## 6. Calin Murariu: reservations + reassignment

| When | Task | Waits on |
|---|---|---|
| **W5 (now)** | Align `ReservationSeeder` with Liviu's `CatalogSeeder` (bookings on an asset that breaks in the demo, free substitutes of the same model) | Liviu's seeder |
| W6 | **Booking on the asset details page** (reuse the calendar view from #87; friendly 409 `RESERVATION_CONFLICT`) | Liviu's asset details page |
| W7 | Listener for `AssetRetiredEvent` if the team chooses it · **ArchUnit strict** after M1 · Observer doc with Stefania | Retire decision |
| W9 | Full incident → reassignment flow locally and on AWS · keep `src/api/reservations.ts` current for Matei's desk page · check times on AWS (container runs in UTC) | Matei's deploy |
| After M1 | Decide what to do with drafts #58, #59, #61 (merge only if time and the team agrees) | – |
| W10–W12 | **Test lead**: keep `docs/testing/test-plan.md` current (#101), JaCoCo results, **test report** · full-flow integration tests · coverage ≥ 70% (`reservation.service`, `reassignment`) · reservation state + sequence diagrams · **Strategy pattern doc** · report section · demo part | – |

## 7. Stefania: incidents, user admin, frontend lead

| When | Task | Waits on |
|---|---|---|
| **W5 (now)** | **Confirm 🟡 ADR-002** in `docs/decisions.md` (Spring serves the React build in prod): issue #105 | – (blocks Matei's Dockerfile) |
| W5 | **Photo upload** + `GET /api/v1/incidents/{id}/photo` (reporter or admin, else 404; frontend loads it as a blob) | – (#90 on `main`) |
| W5 | Triage: **start repair** and **reject** next to the existing resolve; `markAvailable` only if no other `OPEN`/`IN_REPAIR` incident on the asset | – |
| W5 | `GET /api/v1/incidents/me` + `GET /api/v1/incidents/{id}` (for My incidents) | – |
| W5 | **User admin endpoints** in `user/admin/` (search, details, enable/disable, change role; `CANNOT_MODIFY_SELF`, `LAST_ADMIN_REQUIRED`; audit every change) | – |
| W5–W6 | Frontend: **report-incident form** (asset, description, severity, photo with preview) · **register page** | – |
| W6 | Wire all M1 pages together · run the full M1 flow with the team · workload retro (re-split the frontend if it is behind) | Section 2 |
| W7 | **My incidents** + **incident details** pages · **admin incident queue** (filters, photo, start-repair/resolve/reject) · Observer doc draft with Calin | – |
| W8 | **Reassignment log page** · optional admin users page · polish (loading/empty states, error messages by `code`) | – (`/admin/reassignments` on `main`, #96) |
| W9 | Full flow locally and on AWS · responsive layout · give Matei the prod build command and output folder | Matei's deploy |
| W10–W12 | Coverage ≥ 70% (`incident.service`, user admin) · **manual E2E checklist** · final use case + incident state diagrams · **Observer doc** · **user manual** with screenshots · **demo script** + 2 rehearsals + video backup (you lead the demo) | – |

---

## 8. Everyone

| What | Who |
|---|---|
| Settle the 🟡/⏳ rows in `docs/decisions.md` at the next meeting: meeting time, real week dates + defense date, milestones, ADR-002, reassigned booking stays `CONFIRMED`, `Instant` only in event payloads, entity freeze row | Calin leads |
| Reviews within 24h; same day if it blocks someone | All |
| One personal doc per PR / milestone in `documentation/<Surname_Name>/` (feeds your report section) | All |
| Report (`docs/report/`): each member writes their own chapter (Matei: AWS, security, CI/CD; Liviu: catalog + shared services; Calin: reservations, engine, test report; Stefania: incidents, frontend, user manual) | All |
| W11: two full demo rehearsals, local fallback on one laptop, recorded video | All (Stefania leads) |

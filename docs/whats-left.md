# What's left: per member, until the defense

**As of Tue 10 Nov 2026 (W6).** This file is the one place for **what is still to do**. What is already done is in `README.md` §7 (per member) and `AGENTS.md` §7. The detailed plan for W5–W6 is `docs/plan-w4-w6.md`.

**Keep it current:** when your PR finishes a row, delete the row in the same PR (or tick it in the next status update). If a row is wrong for your module, fix it in a small `docs/` PR.

---

## 1. Milestones

| When | Milestone | Status |
|---|---|---|
| W4 | Prototype demo in Swagger | ✅ runs on `main` since 31 Oct (tag `v0.1.0-prototype`) |
| **Sun 15 Nov (end of W6)** | **M1 `v0.1.0`**: locally, a student registers, logs in, browses, books (conflicts rejected), sees My reservations; an admin adds assets; CI green; Swagger complete | All parts on `main`; left: run the flow, tag (section 2) |
| **Sun 6 Dec (end of W9)** | **M2 `v0.2.0`**: everything locally **and on AWS**, incl. incident with photo → automatic reassignment → notification | Not started on AWS |
| Mon 7 Dec (W10) | **Feature freeze**: tests and bugs only | |
| W11–W12 (14–27 Dec) | Docs, diagrams, report, demo rehearsals, defense `v1.0.0` | Christmas break: finish before it. Real defense date still 🟡 |

## 2. What M1 still needs (by 15 Nov)

| Missing | Owner | Waits on |
|---|---|---|
| ~~`CatalogSeeder` with several assets of the same model~~ | Liviu | ✅ #193 |
| ~~Register page~~ | Stefania | ✅ #107 |
| ~~Catalog page, asset details page, admin inventory page~~ | Liviu | ✅ #196 |
| ~~Booking on the asset details page~~ | Calin | ✅ #226 |
| Run the full M1 flow on a fresh database, tag `v0.1.0` | Matei (with Stefania) | everything above |

## 3. Open PRs right now

| PR | Owner | What | Next step |
|---|---|---|---|
| #263 | Stefania | Only HIGH/CRITICAL incidents take the asset out of use, max 3 open reports per student (#229) | Approved: update branch if behind, then merge |
| #266 | Calin | Cancel a user's upcoming reservations: `ReservationApi` method + admin endpoint (for #229) | Needs a review |
| #270 | Calin | Fixes for the code read-through #267 (holds, locking, lifecycle) | Needs a review |
| #272, #273 | Matei | Project search false hits; project details page errors | Need a review |
| #255, #256 | Calin | Admin equipment analytics: backend + page (#254) | Needs a review; off-plan, see section 6 |
| #258, #260, #262, #265 | Calin | Optimal reassignment (min-cost max-flow, #257), chain-shifting resolver (#259), idempotency (#261), retry with backoff (#264) | Need a review; off-plan, see section 6 |
| #58, #59, #61 | Calin | Simulator, waitlist, recommender (drafts) | Parked: off-plan, look again only after M1 |

Merged since the last update: Calin #247 (fixes for #240, closes it); Liviu #244 (audit date filters, closes #241); Matei #251, #252, #253 (status refresh, image security updates, ECR scan note), #271 (login rejects passwords over 72 bytes).

Open issues: **#140** (Projects: engine, 10 projects, pages and Reserve all are on `main`; left: student-kit models in the catalog seed (Liviu) and the app-wide RO/EN choice (Stefania)), **#229** (Stefania + team decision, PRs #263 and #266), **#245** (retire flow, team decision), code read-through findings per owner: **#267** (Calin), **#268** (Liviu), **#269** (Stefania), feature issues for Calin's PRs: #254, #257, #259, #261, #264.

---

## 4. Matei Necula: cloud, security, auth

| When | Task | Waits on |
|---|---|---|
| W6 | ~~**Terraform part 1** (`infra/`): S3 state bucket, VPC, 1 public subnet + 2 private DB subnets, no NAT, security groups~~ (✅ #249, applied) | – |
| W6 | ~~**Terraform part 2**: photos bucket, ECR, EC2 instance role, SSM parameters `/campus/*`, log group~~ (✅ #250, applied). RDS is written but **off** (`db_enabled=false`) until the W7 deploy | Part 1 |
| W6 | ~~Dockerfile~~ (✅ #114) · ~~run the image locally with `prod`~~ (✅ smoke test, #129) · ~~push to ECR~~ (✅ 9 Nov, tagged with the commit SHA) | Terraform part 2 (ECR) |
| W6 | ~~Swagger check~~ (✅ #184) · run the M1 flow · tag `v0.1.0` · status update | Section 2 |
| W6–W8 | **Projects (#140)**: ~~design + plan~~ (✅ #232) · ~~engine: library, search, details endpoints~~ (✅ #236) · ~~10 projects~~ (✅ #239) · ~~`/projects` pages with RO/EN switch~~ (✅ #238) · ~~`POST /projects/{slug}/reserve` + "Reserve all"~~ (✅ #243, #248) | Liviu: student-kit models in the seed; Stefania: whole-app i18n choice |
| W6 | ~~End-of-task docs: AGENTS §4 rule + `docs-check` job, required check on `main`~~ (✅ #277, #279, ruleset 10 Nov) | – |
| W6 | ~~Login password length~~ (✅ #271) · open PRs #272, #273 (the other fixes from the code read-through) | Reviews |
| W7–W8 | **First AWS deploy**: EC2 (Docker) + Elastic IP + CloudFront in front (ADR-002); `Authorization` header must reach Spring through CloudFront · flip `db_enabled` to true (creates RDS) · `MAIL_FROM` with a verified SES sender (the app does not start without it) · `ADMIN_EMAIL` / `ADMIN_PASSWORD` in SSM | Dockerfile, Terraform ✅ |
| W8 | **`deploy.yml`** with GitHub OIDC (no AWS keys in GitHub) · ~~`S3StorageService`~~ (✅ #129, #177) · first live deploy **by the end of W8** | – |
| W9 | ~~`SesNotificationService`~~ (✅ #129, #177) · SES sandbox + verified team emails · all prod secrets in SSM · M2 flow on AWS with Calin + Stefania · tag `v0.2.0` | – |
| W9 | ~~Frontend: **admin reservations desk page**~~ (✅ #138, #194) | – |
| W9 (stretch) | SQS bridge + consumer + DLQ, only if M2 is done and there is time | – |
| W10 | ~~Security pass~~ (✅ `SecurityPassIT`, #139) · ~~PIT survivors in my code~~ (✅ #182) · load smoke test · restore an RDS snapshot once · cost check | – |
| W6–W10 | **Proposed, not agreed (section 7a):** build the **pickup pass** (spec + plan in `docs/superpowers/`) · small asks from teammates' features: the `SecurityConfig` rule for Calin's calendar feed (land it first), the live push call in `SesNotificationService` for Liviu's live notifications, maybe one async-dispatch line in `SecurityConfig` | Section 7a |
| W11–W12 | ~~Deployment + component diagrams~~ (✅ #186; update the AWS one after the deploy) · ~~security chapter~~ (✅ #185) · CI/CD description · runbook · demo on AWS + local fallback + video · `terraform destroy` after grading | – |

## 5. Liviu Nedelcu: catalog + shared services

| When | Task | Waits on |
|---|---|---|
| W6 | ~~**`CatalogSeeder`**~~ (✅ #193) | – |
| ~~W6~~ | ~~Catalog, asset details, admin inventory pages~~ (✅ #196) · ~~`docs/modules/common.md`~~ (✅ #197) · ~~Swagger error codes + PIT survivors~~ (✅ #220, closes #187) | – |
| W6 | ~~Audit date filters~~ (✅ #244) · work through #268 (lost updates on edit, retired asset by id, duplicated paging checks) | – |
| W7 | **Retire flow**: agree with Calin + Stefania what happens to a retired asset's future bookings (recommended: `AssetRetiredEvent`, handled by Calin's engine) | Team decision |
| ~~W7–W8~~ | ~~`STALE_VERSION` test~~ (✅ #220) · ~~audit log endpoint + page~~ (✅ #217, #221) · ~~class + ER diagrams, report chapter~~ (✅ #222, keep them current) | – |
| Proposed, not agreed | Build **live notifications** in your `notification` module (spec + plan `docs/superpowers/*/2026-11-10-live-notifications*`) · optional: add `PICKUP_PASS_VERIFIED` to the audit page's action filter (Matei's pickup pass) | Section 7a |
| W8 | Help Calin with substitute-query edge cases (none, several, retired excluded) | – |
| W10–W12 | Coverage ≥ 70% (`catalog.service`, `common.audit`, `storage`, handler) · final class, ER and Asset state diagrams · report section · demo part (admin adds an asset, student filters, audit log) | – |

## 6. Calin Murariu: reservations + reassignment

| When | Task | Waits on |
|---|---|---|
| ~~W6~~ | ~~Align `ReservationSeeder` with `CatalogSeeder`~~: ✅ it books asset 1, a DS1054Z with 2 more units of the same model (#193) | – |
| ~~W6~~ | ~~Booking on the asset details page~~ (✅ #226) · ~~Observer doc~~ (✅ #207) · bundle, extend, transfer, hold backends merged (#212–#214, #219) | – |
| ~~W6~~ | ~~Finish #224, #225, #227~~ (✅) · ~~#228 security findings~~ (✅ #234) · ~~#231 offsets~~ (✅ #233) · ~~`bookBundle` for Projects~~ (✅ #235) | – |
| **W6 (now)** | **No new features** until the team agrees which off-plan ones stay for the demo. Open off-plan PRs: analytics #255, #256; optimal reassignment #258; chain shifting #260; idempotency #262; retry #265 | Team decision |
| W6 | Review and merge **#270** (fixes for #267) and **#266** (cancel a user's upcoming reservations, for #229) · work through the rest of #267 (reassignment error handling, hold races, size of `ReservationService`) | Reviews |
| W6 | Chain shifting (#260, off-plan): review fixes done (checkout blocks the plan, rollback IT); open: team decides if it stays, and the 🟡 migration for a nullable `incident_id` (#245) | Team decision |
| W7 | Listener for `AssetRetiredEvent` if the team chooses it · **ArchUnit strict** after M1 · Observer doc with Stefania | Retire decision |
| W9 | Full incident → reassignment flow locally and on AWS · keep `src/api/reservations.ts` current for Matei's desk page · check times on AWS (container runs in UTC) | Matei's deploy |
| Proposed, not agreed | Build the **calendar feed** in your `reservation` module (spec + plan `docs/superpowers/*/2026-11-10-calendar-feed*`) · for Stefania's reliability page: a read method on `ReservationApi` for the minutes each asset was really in use (proposed `usedMinutesByAsset`) | Section 7a |
| After M1 | Decide what to do with drafts #58, #59, #61 (merge only if time and the team agrees) | – |
| W10–W12 | **Test lead**: keep `docs/testing/test-plan.md` current (#101, #199), JaCoCo results, **test report** · full-flow integration tests · ~~PIT survivors~~ (✅ #201) · reservation state diagram · ~~booking sequence diagram~~ (✅ #204) · ~~**Strategy pattern doc**~~ (✅ #205) · ~~report section~~ (✅ #203, keep it current) · demo part | – |

## 7. Stefania: incidents, user admin, frontend lead

| When | Task | Waits on |
|---|---|---|
| ~~W5~~ | ~~ADR-002 ✅~~ (#111) · ~~photo upload + view~~ (#210) · ~~start repair, reject~~ (#108) · ~~`GET /incidents/me`, `GET /incidents/{id}`~~ (#108) · ~~user admin endpoints~~ (#206) · ~~register page~~ (#107) | ✅ |
| ~~W6~~ | ~~Report-incident form + My incidents page~~ (✅ #216) | – |
| W6 | Merge **#263** (approved) · work through #269 (concurrency tests, `ci.yml` guards, small incident fixes) | – |
| W6 | Wire all M1 pages together · run the full M1 flow with the team · workload retro (re-split the frontend if it is behind) | Section 2 |
| Proposed, not agreed | Build **equipment reliability** in your `incident` module (spec + plan `docs/superpowers/*/2026-11-10-equipment-reliability*`) · mount Liviu's `<LiveToasts />` once in `AppLayout.tsx` · OK for the routes and menu entries `pickup-pass` (Matei) and `calendar-sync` (Calin) · two QR libraries in `package.json` for Matei's last pickup-pass PR | Section 7a |
| W7 | **Incident details** page · ~~admin incident queue~~ (✅ #223) | – |
| W8 | **Reassignment log page** · optional admin users page (endpoints on `main`, #206) · polish (loading/empty states, error messages by `code`) | – (`/admin/reassignments` on `main`, #96) |
| W9 | Full flow locally and on AWS · responsive layout · give Matei the prod build command and output folder | Matei's deploy |
| W10–W12 | Coverage ≥ 70% (`incident.service`, user admin) · **manual E2E checklist** · final use case + incident state diagrams · **Observer doc** · **user manual** with screenshots · **demo script** + 2 rehearsals + video backup (you lead the demo) | – |

## 7a. Proposed new features (not agreed yet)

Matei proposes four more features, **one per member**: each goes to the member who owns the data it reads, so every member has a feature beyond CRUD to defend and the asks between members stay small. Specs and plans: `docs/superpowers/specs/2026-11-10-*` and `docs/superpowers/plans/2026-11-10-*` (written by Matei; each owner reads, changes and writes the code of their own). **Proposed only:** nothing is started and the split is **not agreed**: raise it at the next meeting. The four can be built in parallel. All four must be done before the feature freeze (Mon 7 Dec) and must not delay M1 or the W7 deploy. If a teammate part is not on `main` by **30 Nov**, the cut line in the table applies.

| # | Feature | What it is | Owner | Needs from | Cut line |
|---|---|---|---|---|---|
| 1 | Pickup pass | A rotating 6-digit code (and QR) a student shows at the lab desk; the desk page verifies it | **Matei** (`user/`, his desk page) | Stefania: OK for the route and menu entry; later two QR libraries. Liviu: optional audit filter entry | PRs 1 and 2 are the feature; drop the QR PR 3 if the libraries are not in by 30 Nov |
| 2 | Calendar feed | A private link a student subscribes to in Google/Apple/Outlook calendar; bookings, cancels and reassignments show up by themselves | **Calin** (`reservation/`, new table `calendar_feeds`) | Matei: one `SecurityConfig` rule (public feed path). Stefania: OK for the route and menu entry | All three PRs and Matei's rule are needed; Matei's rule lands first |
| 3 | Live notifications | A message on screen the moment a booking is moved | **Liviu** (`notification/`, no event needed) | Stefania: mount `<LiveToasts />` once. Matei: the same push call in `SesNotificationService` (prod), maybe one `SecurityConfig` line | Works locally without Matei's line; on AWS only with it. Dropped from the AWS demo if it is not in by 30 Nov |
| 4 | Equipment reliability | "Which units keep breaking?" admin page | **Stefania** (`incident/`, reads her own incidents) | Calin: a read method on `ReservationApi` (used minutes per asset) | First to drop: if Calin's method is not on `main` by 30 Nov, stop after PR 1 |

---

## 8. Everyone

| What | Who |
|---|---|
| Settle the 🟡/⏳ rows in `docs/decisions.md` at the next meeting: meeting time, real week dates + defense date, milestones, retired asset's future bookings, `Instant` only in event payloads, entity freeze row | Calin leads |
| Reviews within 24h; same day if it blocks someone | All |
| One personal doc per PR / milestone in `documentation/<Surname_Name>/` (feeds your report section) | All |
| Report (`docs/report/`): each member writes their own chapter (Matei: AWS, security, CI/CD; Liviu: catalog + shared services; Calin: reservations, engine, test report; Stefania: incidents, frontend, user manual) | All |
| W11: two full demo rehearsals, local fallback on one laptop, recorded video | All (Stefania leads) |

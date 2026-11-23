# What's left: per member, until the defense

**As of Fri 20 Nov 2026 (W7).** This file is the one place for **what is still to do**. What is already done is in `README.md` §7 (per member) and `AGENTS.md` §7. The detailed plan for W5–W6 is `docs/plan-w4-w6.md`.

**Keep it current:** when your PR finishes a row, delete the row in the same PR (or tick it in the next status update). If a row is wrong for your module, fix it in a small `docs/` PR.

---

## 1. Milestones

| When | Milestone | Status |
|---|---|---|
| W4 | Prototype demo in Swagger | ✅ runs on `main` since 31 Oct (tag `v0.1.0-prototype`) |
| **Sun 15 Nov (end of W6)** | **M1 `v0.1.0`**: locally, a student registers, logs in, browses, books (conflicts rejected), sees My reservations; an admin adds assets; CI green; Swagger complete | ✅ flow run on a fresh database (API + browser), `v0.1.0` tagged on `b81e400` (11 Nov) |
| **Sun 6 Dec (end of W9)** | **M2 `v0.2.0`**: everything locally **and on AWS**, incl. incident with photo → automatic reassignment → notification | First AWS deploy live on 17 Nov (#304), paused to save credits; left: `deploy.yml`, M2 flow on AWS |
| Mon 7 Dec (W10) | **Feature freeze**: tests and bugs only | |
| W11–W12 (14–27 Dec) | Docs, diagrams, report, demo rehearsals, defense `v1.0.0` | Christmas break: finish before it. Real defense date still 🟡 |

## 2. M1 ✅ (11 Nov)

Every M1 part is on `main` (seed data #193, register #107, catalog + inventory pages #196, booking on the asset details #226). Matei ran the full flow on a fresh database on 11 Nov (register, login, browse, book, conflict 409, My reservations, admin adds an asset, Swagger) and tagged **`v0.1.0`** on `b81e400`. Next milestone: **M2 by Sun 6 Dec** (section 1).

## 3. Open PRs right now

None right now (20 Nov afternoon).

Merged since the last update: Matei #312 (`findActiveRole` reads the row fresh), #318 (QR pickup pass, desk camera scan), #320 (`deploy.yml` with GitHub OIDC), #321, #323 (team decisions, 20 Nov), #332 (SES notifications live to open tabs); Calin #310 (re-check after quota lock), #311 (retired asset → reassignment), #315 (Calendar sync button), #316 (PIT survivors), #326 (`triggered_by` rename), #327 (source-asset guard on moves), #328 ("Show pickup pass" button), #329 (row lock on disable cancel), #330 (cancel bookings of disabled users), #333 (`ReservationApi` overload cleanup); Stefania #302 (`UserDisabledEvent` on disable), #331 (admin Users and Reassignment log pages); Liviu #317 (live notifications stream). Closed issues: #245, #289, #305, #306, #314, #319.

Open issues: **#140** (Matei, Projects: left: the app-wide RO/EN choice, Stefania), **#229** (Stefania: all its PRs are merged, can be closed), **#268** (Liviu, code read-through), **#269** (Stefania, code read-through), **#290** (Liviu, live notifications: frontend left), **#291** (Stefania, equipment reliability), **#322** (Calin), **#324** (Liviu), **#325** (Stefania): what's left until the defense (decisions of 20 Nov).


---

## 4. Matei Necula: cloud, security, auth

| When | Task | Waits on |
|---|---|---|
| W6 | ~~**Terraform part 1** (`infra/`): S3 state bucket, VPC, 1 public subnet + 2 private DB subnets, no NAT, security groups~~ (✅ #249, applied) | – |
| W6 | ~~**Terraform part 2**: photos bucket, ECR, EC2 instance role, SSM parameters `/campus/*`, log group~~ (✅ #250, applied). RDS is written but **off** (`db_enabled=false`) until the W7 deploy | Part 1 |
| W6 | ~~Dockerfile~~ (✅ #114) · ~~run the image locally with `prod`~~ (✅ smoke test, #129) · ~~push to ECR~~ (✅ 9 Nov, tagged with the commit SHA) | Terraform part 2 (ECR) |
| W6 | ~~Swagger check~~ (✅ #184) · ~~run the M1 flow · tag `v0.1.0`~~ (✅ 11 Nov) · ~~status update~~ (✅ 16 Nov) | – |
| W6–W8 | **Projects (#140)**: ~~design + plan~~ (✅ #232) · ~~engine: library, search, details endpoints~~ (✅ #236) · ~~10 projects~~ (✅ #239) · ~~`/projects` pages with RO/EN switch~~ (✅ #238) · ~~`POST /projects/{slug}/reserve` + "Reserve all"~~ (✅ #243, #248) | Liviu: student-kit models in the seed; Stefania: whole-app i18n choice |
| W6 | ~~End-of-task docs: AGENTS §4 rule + `docs-check` job, required check on `main`~~ (✅ #277, #279, ruleset 10 Nov) | – |
| W6 | ~~Login password length~~ (✅ #271) · ~~the other fixes from the code read-through~~ (✅ #272, #273) | – |
| W7–W8 | **First AWS deploy**: infrastructure applied on 17 Nov and live in (#304) (EC2 t3.small + Elastic IP + CloudFront, `db_enabled` true, SES sender, `MAIL_FROM` / `ADMIN_*` in SSM); SES sender verified, app live on CloudFront; Live checks found two small bugs, both fixed in #308 (Swagger "Try it out" server URL, `HEAD /` 401); **left: W8 `deploy.yml`** (written, #320; see the W8 row). Plan: EC2 (Docker) + Elastic IP + CloudFront in front (ADR-002); `Authorization` header must reach Spring through CloudFront · flip `db_enabled` to true (creates RDS) · `MAIL_FROM` with a verified SES sender (the app does not start without it) · `ADMIN_EMAIL` / `ADMIN_PASSWORD` in SSM | Dockerfile, Terraform ✅ |
| W7 | **AWS paused 17 Nov** to save credits (EC2 stopped, then RDS; about $0.25/day instead of $1.2). AWS restarts a stopped RDS after 7 days: ~~e-mail alert via SNS + RDS event subscription~~ written in #309, applied 17 Nov (4 added); **left: click the SNS confirmation link once, then resume before the demo/W8 `deploy.yml`** (resume steps in `documentation/Necula_Matei/56-rds-auto-start-alert.md`) | #309 confirm link |
| W8 | ~~**`deploy.yml`** with GitHub OIDC (no AWS keys in GitHub)~~ written (#320: OIDC role limited to `main`, build + push + SSM `campus-deploy` + health check, skips while AWS is paused) · ~~`S3StorageService`~~ (✅ #129, #177) · **left: `terraform apply` (3 to add), repo variables `AWS_DEPLOY_ROLE_ARN` + `APP_URL`, first live run after the resume** (steps in `documentation/Necula_Matei/59-deploy-workflow.md`) **by the end of W8** | AWS resume |
| W9 | ~~`SesNotificationService`~~ (✅ #129, #177) · SES sandbox + verified team emails · all prod secrets in SSM · M2 flow on AWS with Calin + Stefania · tag `v0.2.0` | – |
| W9 | ~~Frontend: **admin reservations desk page**~~ (✅ #138, #194) | – |
| W9 (stretch) | SQS bridge + consumer + DLQ, only if M2 is done and there is time | – |
| W7 | ~~`UserApi.findActiveRole` reads the row fresh~~ (✅ #312; found in the review of Calin's #310, so a disable is seen by the re-check after the quota lock). Calin fixed the order in his paths (✅ #310) | – |
| W7 | ~~`UserDisabledEvent` Javadoc~~ (✅ #335: listener contract corrected, not `@Async` unlike the reassignment listeners, `REQUIRES_NEW` needed after commit; found in the review of Calin's #330, Refs #229) | – |
| W10 | ~~Security pass~~ (✅ `SecurityPassIT`, #139) · ~~PIT survivors in my code~~ (✅ #182) · load smoke test · restore an RDS snapshot once · cost check | – |
| W6–W10 | **Proposed, not agreed (section 7a):** build the **pickup pass** (spec + plan in `docs/superpowers/`): PR 1 (backend, both endpoints) is done in #283, PR 2 (student page + desk Verify dialog) is done in #284, PR 3 (QR image on the pass page + camera scan in the Verify dialog) is done in #318 (libraries from #303); Calin's "Show pickup pass" button in My reservations is done (✅ #328) · small asks from teammates' features: ~~the `SecurityConfig` rule for Calin's calendar feed (land it first)~~ (✅ #295), ~~the live push call in `SesNotificationService` for Liviu's live notifications~~ (✅ #332), maybe one async-dispatch line in `SecurityConfig` | Section 7a |
| W11–W12 | ~~Deployment + component diagrams~~ (✅ #186; update the AWS one after the deploy) · ~~security chapter~~ (✅ #185) · CI/CD description · runbook · demo on AWS + local fallback + video · `terraform destroy` after grading | – |
| W8 | ~~**Scheduler pool**~~ (written, #347): `@Scheduled` jobs run on 3 threads (`campus-sched-`) instead of 1, so Liviu's SSE heartbeat is not delayed by Calin's sweeps (CloudFront closes idle streams after 30 s) | – |

## 5. Liviu Nedelcu: catalog + shared services

| When | Task | Waits on |
|---|---|---|
| W6 | ~~**`CatalogSeeder`**~~ (✅ #193) · ~~student-kit models seeded for Projects (#140)~~ (✅ #275) | – |
| ~~W6~~ | ~~Catalog, asset details, admin inventory pages~~ (✅ #196) · ~~`docs/modules/common.md`~~ (✅ #197) · ~~Swagger error codes + PIT survivors~~ (✅ #220, closes #187) | – |
| W6 | ~~Audit date filters~~ (✅ #244) · ~~#268 bugs: asset version on edit, retired asset by id, audit diffs, details flicker~~ (✅ #278) · ~~#268 backend cleanup (`PageRequests`, `CatalogPersistence`, `CatalogService.findOrThrow`, Javadoc)~~ (✅ #287) · ~~#268 frontend split (`AdminInventoryPage`, `statusBadgeColor`, isolated modal errors)~~ (✅ #288) · remaining #268 cleanup (optional `category`/`labRoom` version) | – |
| W7 | **Retire flow**: agree with Calin + Stefania (✅ agreed in #245) · publish `AssetRetiredEvent` in `catalog.api` for reassignment flow (✅ #280) | Calin's listener in reassignment |
| ~~W7–W8~~ | ~~`STALE_VERSION` test~~ (✅ #220) · ~~audit log endpoint + page~~ (✅ #217, #221) · ~~class + ER diagrams, report chapter~~ (✅ #222, keep them current) | – |
| W7–W9 (agreed 16 Nov) | **Live notifications (#290)** (`docs/superpowers/*/2026-11-10-live-notifications*`): ~~PR 1 (`LiveStreams`, `LiveStreamController`, `LiveEvent`, `LiveStreamIT`, api-contract)~~ (✅ #317) · ~~PR 2 (frontend `live.ts`, `LiveToasts.tsx`)~~ (✅ #339) · ~~PR 3 (push in `LoggingNotificationService`, `UserDisabledLiveStreamListener`, `LiveStreamIT`)~~ (✅ #340) · ~~optional: add `PICKUP_PASS_VERIFIED` to the audit page's action filter~~ (✅ #288) | Section 7a (Stefania mounts `<LiveToasts />`) |
| W8 | Help Calin with substitute-query edge cases (none, several, retired excluded) | – |
| W10–W12 | Coverage ≥ 70% (`catalog.service`, `common.audit`, `storage`, handler) · final class, ER and Asset state diagrams · report section · demo part (admin adds an asset, student filters, audit log) | – |

## 6. Calin Murariu: reservations + reassignment

| When | Task | Waits on |
|---|---|---|
| ~~W6~~ | ~~Align `ReservationSeeder` with `CatalogSeeder`~~: ✅ it books asset 1, a DS1054Z with 2 more units of the same model (#193) | – |
| ~~W6~~ | ~~Booking on the asset details page~~ (✅ #226) · ~~Observer doc~~ (✅ #207) · bundle, extend, transfer, hold backends merged (#212–#214, #219) | – |
| ~~W6~~ | ~~Finish #224, #225, #227~~ (✅) · ~~#228 security findings~~ (✅ #234) · ~~#231 offsets~~ (✅ #233) · ~~`bookBundle` for Projects~~ (✅ #235) | – |
| ~~W6~~ | ~~**No new features** until the team agrees~~ → settled on 20 Nov (#321, #323, #322): keep #59 FIFO waitlist, #58 maintenance impact simulator, #61 next free slots (first PR on `main` by 30 Nov, all done by 7 Dec); #262 idempotency merged | – |
| W6 | ~~Review and merge **#270**~~ (✅ #270: fixes for #267) · ~~**#266**~~ (✅ #266: `cancelUpcomingReservationsForUser` facade + admin endpoint, Refs #229; `UserDisabledReservationListener` wired on `UserDisabledEvent` (#302, #330, Refs #229); disabled-user booking race closed by re-checking `userApi.findActiveRole` after `lockUserQuota` (#310); #310 concurrency follow-ups (`FOR UPDATE` in `cancelUpcomingReservationsForUser`, `findByIdForUpdate` in `acceptTransfer`, `pg_stat_activity` polling in `ReservationConcurrencyIT`, #329, Refs #229, #322)) · ~~`isAssetFree` hold owner exclusion, `CalendarBookingView` week-nav guard, test & ArchUnit hardening~~ (✅ #293, Refs #267) · ~~single hold sweep, repository overlap query simplification, `ReservationHold`/`ReservationService` cleanups~~ (✅ #294, Refs #267) · ~~reservation frontend constants/`useSlotHold`/modals refactor~~ (✅ #292, Refs #267) · extracted `HoldService`, `TransferService`, and `ReservationSlotValidator` and simplified `ReservationService` to one constructor (#296, Refs #267 §2) | Reviews |
| W6 | **#262** (idempotency key): backend review fixes done (FAILED retry, 60 s takeover, doc 31) and `frontend/src/api/reservations.ts` now sends a slot-scoped `Idempotency-Key` on `POST /reservations`; left open: the team decision on this off-plan feature | Team decision |
| W7 | ~~Listener for `AssetRetiredEvent` + nullable `reassignment_log.incident_id`~~ (✅ #311, Refs #245; #311 review nits: `triggered_by` column + generated IT IDs, #326; concurrent incident/retire source-asset guard, #327 Closes #319; removed self-invoking `default` overloads on `ReservationApi` + `CyclicBarrier` race IT, #333; #329/#333 review nits + PIT survivors: `cancelReservation` `FOR UPDATE`, deterministic accept/reject lock ITs, ArchUnit Rule 15 no-default-in-api, transfer lock-order PIT assertions, docs sync, #336; #336 review nits: `cancelBundle` `findByBundleIdForUpdate` ordered by `id`, `markNoShow` `FOR UPDATE`, deterministic `cancelReservation` & `cancelBundle` race ITs, personal docs 02/11/15 sync, #338; #338/#346 follow-ups: `markNoShow` locked `userId` notification, `checkOut`-vs-`markNoShow` race IT, `Clock` dates in `ReservationRepositoryTest`, generated IDs in `ReservationConcurrencyIT` & `ReassignmentRollbackIT`, #348) · `"Show pickup pass"` button on today's `CONFIRMED` bookings in `MyReservationsPage` (#328, Refs #322) · ~~**ArchUnit strict** after M1~~ (✅ already strict, no frozen violations) · Observer doc with Stefania | – |
| W9 | Full incident → reassignment flow locally and on AWS · keep `src/api/reservations.ts` current for Matei's desk page · check times on AWS (container runs in UTC) | Matei's deploy |
| Proposed, not agreed | **Calendar feed (#289)** (`docs/superpowers/*/2026-11-10-calendar-feed*`): ~~PR 1 (`calendar_feeds` migration, `CalendarTokenService`, `/me/calendar-feed` settings endpoints, `IcsWriter`)~~ (✅ #299) · ~~PR 2 (`ReservationRepository.findByUserIdOverlappingRange`, `CalendarFeedService`, `GET /api/v1/calendar/{token}.ics` in `CalendarFeedController`)~~ (✅ #300) · ~~PR 3 (frontend `CalendarSyncPage`)~~ (✅ #297) · ~~moved Calendar sync into a button on `MyReservationsPage` and removed the sidebar entry~~ (✅ #315, Closes #314) · ~~for Stefania's reliability page: a read method on `ReservationApi` for the minutes each asset was really in use (`usedMinutesByAsset`)~~ (✅ #298, Refs #289) | – |
| After M1 | Decide what to do with drafts #58, #59, #61 (merge only if time and the team agrees) · **#286** (#285) same-model substitute suggestions on booking conflict in `checkAvailability` + `CalendarBookingView` (in-place unit switch) | – |
| W10–W12 | **Test lead**: keep `docs/testing/test-plan.md` current (#101, #199), JaCoCo results, **test report** · full-flow integration tests · ~~PIT survivors~~ (✅ #201) · reservation state diagram · ~~booking sequence diagram~~ (✅ #204) · ~~**Strategy pattern doc**~~ (✅ #205) · ~~report section~~ (✅ #203, keep it current) · demo part | – |

## 7. Stefania: incidents, user admin, frontend lead

| When | Task | Waits on |
|---|---|---|
| ~~W5~~ | ~~ADR-002 ✅~~ (#111) · ~~photo upload + view~~ (#210) · ~~start repair, reject~~ (#108) · ~~`GET /incidents/me`, `GET /incidents/{id}`~~ (#108) · ~~user admin endpoints~~ (#206) · ~~register page~~ (#107) | ✅ |
| ~~W6~~ | ~~Report-incident form + My incidents page~~ (✅ #216) | – |
| W6 | ~~Merge **#263**~~ (✅ #263: only HIGH/CRITICAL take the asset out of use, max 3 open reports, part 1 of #229) · **#229 part 2:** `UserDisabledEvent` published on disable (#302); Calin adds the listener that calls #266 (AFTER_COMMIT + `REQUIRES_NEW`, not `@Async`) · work through #269 (concurrency tests, `ci.yml` guards, small incident fixes) | – |
| W6 | Wire all M1 pages together · run the full M1 flow with the team · workload retro (re-split the frontend if it is behind) | Section 2 |
| Proposed, not agreed | Build **equipment reliability** in your `incident` module (spec + plan `docs/superpowers/*/2026-11-10-equipment-reliability*`): ~~PR 1 `ReliabilityCalculator` + tests~~ (✅ Refs #291) · ~~PR 2 `ReliabilityService` + `GET /api/v1/admin/reliability`~~ (✅ Refs #291, uses Calin's `usedMinutesByAsset`, #298) · ~~PR 3 **Equipment health** page `/admin/reliability`~~ (✅ Refs #291) · ~~mount Liviu's `<LiveToasts />` once in `AppLayout.tsx`~~ (✅ #345, Refs #290) · OK for the routes and menu entries `pickup-pass` (Matei) and `calendar-sync` (Calin) · ~~two QR libraries in `package.json` for Matei's last pickup-pass PR~~ (✅ `qrcode.react` + `@yudiel/react-qr-scanner`, Refs #291; Matei: PR 3 can start) | Section 7a |
| W7 | #269 point 2: ~~`hashFiles` guards in `ci.yml`~~ (✅ removed, Refs #269) · rest of #269 still open | – |
| W7 | ~~**Incident details** page~~ (✅ `/incidents/:id`, linked from My incidents, the queue and the Reassignment log) · ~~admin incident queue~~ (✅ #223) | – |
| W8 | ~~**Reassignment log page** (#306)~~ ✅ · ~~**admin users page, mandatory** (#305; the only way to promote an admin or disable an account on AWS; endpoints on `main`, #206)~~ ✅ (both in my Users + Reassignment log PR, 20 Nov) · polish (loading/empty states, error messages by `code`) | – (`/admin/reassignments` on `main`, #96) |
| W9 | Full flow locally and on AWS · responsive layout · give Matei the prod build command and output folder | Matei's deploy |
| W10–W12 | Coverage ≥ 70% (`incident.service`, user admin) · **manual E2E checklist** · final use case + incident state diagrams · **Observer doc** · **user manual** with screenshots · **demo script** + 2 rehearsals + video backup (you lead the demo) | – |

## 7a. New features, one per member (agreed 16 Nov)

Matei proposes four more features, **one per member**: each goes to the member who owns the data it reads, so every member has a feature beyond CRUD to defend and the asks between members stay small. Specs and plans: `docs/superpowers/specs/2026-11-10-*` and `docs/superpowers/plans/2026-11-10-*` (written by Matei; each owner reads, changes and writes the code of their own). **Agreed by the team on 16 Nov** (`docs/decisions.md` §6); tracking issues #289 (Calin), #290 (Liviu), #291 (Stefania); Matei's pickup pass backend is on `main` (#283). The four can be built in parallel. All four must be done before the feature freeze (Mon 7 Dec) and must not delay M1 or the W7 deploy. If a teammate part is not on `main` by **30 Nov**, the cut line in the table applies.

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

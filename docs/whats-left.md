# What's left: per member, until the defense

**As of Mon 9 Nov 2026 (W6).** This file is the one place for **what is still to do**. What is already done is in `README.md` §7 (per member) and `AGENTS.md` §7. The detailed plan for W5–W6 is `docs/plan-w4-w6.md`.

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
| #58, #59, #61 | Calin | Simulator, waitlist, recommender (drafts) | Parked: off-plan, look again only after M1 |

Merged since the last update: Calin #224, #225, #227, #233, #234 (closes #228), #235 (`bookBundle`); Matei #232, #236 (Projects engine).

Open issues: **#140** (Projects: engine on `main`; left: 9 more projects, student-kit models in the catalog seed (Liviu), pages, "Reserve all"), **#229** (Stefania + team decision), **#230** (Liviu).

---

## 4. Matei Necula: cloud, security, auth

| When | Task | Waits on |
|---|---|---|
| **W6 (now)** | **Terraform part 1** (`infra/`): S3 state bucket, VPC, 1 public subnet + 2 private DB subnets, no NAT, security groups (EC2 only from CloudFront, RDS only from EC2); `terraform plan` | – |
| W6 | **Terraform part 2**: RDS PostgreSQL 18 (`db.t4g.micro`, private, encrypted), S3 photos bucket, ECR, EC2 instance role, SSM parameters `/campus/*`; first `apply`, stop RDS when idle | Part 1 |
| W6 | ~~Dockerfile~~ (✅ #114) · ~~run the image locally with `prod`~~ (✅ smoke test, #129) · push to ECR | Terraform part 2 (ECR) |
| W6 | ~~Swagger check~~ (✅ #184) · run the M1 flow · tag `v0.1.0` · status update | Section 2 |
| W6–W8 | **Projects (#140)**: ~~design + plan~~ (✅ #232) · ~~engine: library, search, details endpoints~~ (✅ #236) · 9 more projects (PR 3) · `/projects` pages with RO/EN switch (PR 4) · `POST /projects/{slug}/reserve` + "Reserve all" (PR 5, uses `bookBundle` ✅ #235) | Liviu: student-kit models in the seed; Stefania: whole-app i18n choice |
| W7–W8 | **First AWS deploy**: EC2 (Docker) + CloudFront in front (ADR-002); `Authorization` header must reach Spring through CloudFront | Dockerfile, Terraform |
| W8 | **`deploy.yml`** with GitHub OIDC (no AWS keys in GitHub) · ~~`S3StorageService`~~ (✅ #129, #177) · first live deploy **by the end of W8** | – |
| W9 | ~~`SesNotificationService`~~ (✅ #129, #177) · SES sandbox + verified team emails · all prod secrets in SSM · M2 flow on AWS with Calin + Stefania · tag `v0.2.0` | – |
| W9 | ~~Frontend: **admin reservations desk page**~~ (✅ #138, #194) | – |
| W9 (stretch) | SQS bridge + consumer + DLQ, only if M2 is done and there is time | – |
| W10 | ~~Security pass~~ (✅ `SecurityPassIT`, #139) · ~~PIT survivors in my code~~ (✅ #182) · load smoke test · restore an RDS snapshot once · cost check | – |
| W11–W12 | ~~Deployment + component diagrams~~ (✅ #186; update the AWS one after the deploy) · ~~security chapter~~ (✅ #185) · CI/CD description · runbook · demo on AWS + local fallback + video · `terraform destroy` after grading | – |

## 5. Liviu Nedelcu: catalog + shared services

| When | Task | Waits on |
|---|---|---|
| W6 | ~~**`CatalogSeeder`**~~ (✅ #193) | – |
| ~~W6~~ | ~~Catalog, asset details, admin inventory pages~~ (✅ #196) · ~~`docs/modules/common.md`~~ (✅ #197) · ~~Swagger error codes + PIT survivors~~ (✅ #220, closes #187) | – |
| W7 | **Retire flow**: agree with Calin + Stefania what happens to a retired asset's future bookings (recommended: `AssetRetiredEvent`, handled by Calin's engine) | Team decision |
| ~~W7–W8~~ | ~~`STALE_VERSION` test~~ (✅ #220) · ~~audit log endpoint + page~~ (✅ #217, #221) · ~~class + ER diagrams, report chapter~~ (✅ #222, keep them current) | – |
| W8 | Help Calin with substitute-query edge cases (none, several, retired excluded) | – |
| W10–W12 | Coverage ≥ 70% (`catalog.service`, `common.audit`, `storage`, handler) · final class, ER and Asset state diagrams · report section · demo part (admin adds an asset, student filters, audit log) | – |

## 6. Calin Murariu: reservations + reassignment

| When | Task | Waits on |
|---|---|---|
| ~~W6~~ | ~~Align `ReservationSeeder` with `CatalogSeeder`~~: ✅ it books asset 1, a DS1054Z with 2 more units of the same model (#193) | – |
| ~~W6~~ | ~~Booking on the asset details page~~ (✅ #226) · ~~Observer doc~~ (✅ #207) · bundle, extend, transfer, hold backends merged (#212–#214, #219) | – |
| ~~W6~~ | ~~Finish #224, #225, #227~~ (✅) · ~~#228 security findings~~ (✅ #234) · ~~#231 offsets~~ (✅ #233) · ~~`bookBundle` for Projects~~ (✅ #235) | – |
| **W6 (now)** | **No new features** until the team agrees which off-plan ones stay for the demo | – |
| W7 | Listener for `AssetRetiredEvent` if the team chooses it · **ArchUnit strict** after M1 · Observer doc with Stefania | Retire decision |
| W9 | Full incident → reassignment flow locally and on AWS · keep `src/api/reservations.ts` current for Matei's desk page · check times on AWS (container runs in UTC) | Matei's deploy |
| After M1 | Decide what to do with drafts #58, #59, #61 (merge only if time and the team agrees) | – |
| W10–W12 | **Test lead**: keep `docs/testing/test-plan.md` current (#101, #199), JaCoCo results, **test report** · full-flow integration tests · ~~PIT survivors~~ (✅ #201) · reservation state diagram · ~~booking sequence diagram~~ (✅ #204) · ~~**Strategy pattern doc**~~ (✅ #205) · ~~report section~~ (✅ #203, keep it current) · demo part | – |

## 7. Stefania: incidents, user admin, frontend lead

| When | Task | Waits on |
|---|---|---|
| ~~W5~~ | ~~ADR-002 ✅~~ (#111) · ~~photo upload + view~~ (#210) · ~~start repair, reject~~ (#108) · ~~`GET /incidents/me`, `GET /incidents/{id}`~~ (#108) · ~~user admin endpoints~~ (#206) · ~~register page~~ (#107) | ✅ |
| ~~W6~~ | ~~Report-incident form + My incidents page~~ (✅ #216) | – |
| W6 | Wire all M1 pages together · run the full M1 flow with the team · workload retro (re-split the frontend if it is behind) | Section 2 |
| W7 | **Incident details** page · ~~admin incident queue~~ (✅ #223) | – |
| W8 | **Reassignment log page** · optional admin users page (endpoints on `main`, #206) · polish (loading/empty states, error messages by `code`) | – (`/admin/reassignments` on `main`, #96) |
| W9 | Full flow locally and on AWS · responsive layout · give Matei the prod build command and output folder | Matei's deploy |
| W10–W12 | Coverage ≥ 70% (`incident.service`, user admin) · **manual E2E checklist** · final use case + incident state diagrams · **Observer doc** · **user manual** with screenshots · **demo script** + 2 rehearsals + video backup (you lead the demo) | – |

---

## 8. Everyone

| What | Who |
|---|---|
| Settle the 🟡/⏳ rows in `docs/decisions.md` at the next meeting: meeting time, real week dates + defense date, milestones, retired asset's future bookings, `Instant` only in event payloads, entity freeze row | Calin leads |
| Reviews within 24h; same day if it blocks someone | All |
| One personal doc per PR / milestone in `documentation/<Surname_Name>/` (feeds your report section) | All |
| Report (`docs/report/`): each member writes their own chapter (Matei: AWS, security, CI/CD; Liviu: catalog + shared services; Calin: reservations, engine, test report; Stefania: incidents, frontend, user manual) | All |
| W11: two full demo rehearsals, local fallback on one laptop, recorded video | All (Stefania leads) |

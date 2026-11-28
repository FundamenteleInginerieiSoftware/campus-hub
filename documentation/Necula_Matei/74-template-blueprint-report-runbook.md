# Reusable template blueprint, AWS & CI/CD report chapter, runbook, and refreshed security/UML docs (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth  
**Date:** 2026-11-28  
**Branch / PR:** `docs/template-blueprint-report-runbook` (#TBD)  
**See also:** `31-security-chapter.md`, `32-component-deployment-diagrams.md`, `54-terraform-deploy.md`, `56-rds-auto-start-alert.md`, `59-deploy-workflow.md`, `68-audit-platform-fixes.md`

---

## Part 1: In simple terms

Before feature freeze and the final FIS report, several of my key documents were still frozen at early November (before the live AWS deploy, GitHub OIDC deploys, pickup pass, calendar feed, live notifications, and Projects landed on `main`), and we still needed my AWS & CI/CD report chapter, an operations runbook, and a clear post-defense reuse plan.

1. **Reusable Template Blueprint (`docs/reusable-template-blueprint.md`):** explains how roughly 70% of what we built (locking, security, live toasts, file checks, Terraform, CI/CD) works for *any* booking-and-incident domain—not just university labs—and maps out the 3 small domain seams and a 7-step (~3–5 day) checklist to turn the repo into a GitHub Template after the `v1.0.0` defense (covering hospitals, film/AV gear, university vehicles, and coworking rooms).
2. **Surfaced in `PROGRESS.md` and `README.md`:** added a compact Template Blueprint section with a 4-domain mapping table to `PROGRESS.md` (so `sync-wiki.yml` publishes it straight to the GitHub Wiki) and added a **Template Blueprint** link to the top navigation bar in `README.md`.
3. **Refreshed Security Chapter (`docs/report/security.md`):** updated from 5 Nov (`99d0261`) to 28 Nov (`545e334`), removing outdated "planned / not on main yet" notes now that incident photos (`#210`), admin user disable (`#206`/`#302`), rotating QR pickup passes (`#283`/`#318`), the calendar feed rule (`#295`), CloudFront Swagger/`HEAD` fixes (`#308`), scalar `findActiveRoleById` (`#312`), GitHub OIDC deploys (`#320`), and thread-pool isolation (`#347`/`#366`) are all live on `main`, while keeping honest gaps (rate limiting, change-password, expired-JWT test) in §9.
4. **AWS & CI/CD Report Chapter (`docs/report/aws-and-cicd.md`):** documents ADRs 001–005, the 3-stage Terraform setup (`infra/`), VPC/SG/IMDSv2/SSM security, the 3-stage CVE-patched Alpine `Dockerfile`, all 4 GitHub Actions workflows (including keyless OIDC `deploy.yml`), and AWS credit cost controls.
5. **Operational Runbook (`docs/runbook.md`):** copy-pasteable commands verified against `infra/` for pausing/resuming AWS, deploying or rolling back to an older commit SHA, running the W10 RDS daily snapshot restore drill, rotating SSM secrets, verifying SES sandbox emails, reading CloudWatch logs, running the local PostgreSQL fallback (including an isolated scratch cluster), and final `terraform destroy` teardown.
6. **Refreshed UML Diagrams (`docs/diagrams/deployment-aws.puml`, `component.puml`, `README.md`):** replaced the old "PLANNED (W7–W9)" AWS deployment diagram with the real deployed stack (including SNS RDS auto-start alert `#309` and OIDC `deploy.yml` `#320`), and updated `component.puml` from real `main` imports to show `project`, `PickupPassService`, `AssetRetiredEvent`, `UserDisabledEvent`, and `LiveStreams` SSE.

**Result:** Matei's report chapters, operational runbook, post-defense reuse blueprint, and UML sources all match `main` (`545e334`) with zero stale 5 Nov claims.

---

## Part 2: Technical details

### Files added / changed

| File | Change |
|---|---|
| `docs/reusable-template-blueprint.md` *(new)* | Post-defense extraction blueprint: ~70% reusable core vs 3 domain seams (`catalog/` taxonomy, `ReservationSlotValidator`/quotas, `SubstituteStrategy` + optional `project/`), 4-industry mapping table, and 7-step (~3–5 day) extraction checklist |
| `docs/report/aws-and-cicd.md` *(new)* | Matei's AWS & CI/CD report chapter: ADR-001..005 summary, 3-stage Terraform IaC + alerts + OIDC, VPC/SG/IMDSv2/CloudFront/SSM/IAM tables, 3-stage `Dockerfile` (`CVE-2026-4738` catch `#252`), `ci.yml`/`docs-check.yml`/`deploy.yml`/`sync-wiki.yml`, live proof & cost profile, Future Work link |
| `docs/runbook.md` *(new)* | Operational runbook covering pause/resume, manual & rollback deploy (`deploy.yml` & SSM `campus-deploy`), non-destructive RDS snapshot restore drill, SSM secret rotation, SES sandbox verification, CloudWatch Logs + SSM Session Manager, local/scratch PostgreSQL 18 fallback, and `terraform destroy` |
| `docs/report/security.md` | Refreshed to `main` `545e334` (28 Nov 2026): updated 10-rule `SecurityConfig` RBAC table, 29 `/api/v1/admin/**` route mappings across 11 controllers, 21 tests in `SecurityConfigTest`, pickup pass, calendar feed, live SSE, scalar `findActiveRoleById`, live AWS controls, and honest §9 gaps + template blueprint pointer |
| `docs/diagrams/deployment-aws.puml` | Removed "PLANNED (W7–W9)"; updated to live deployment (EC2 `t3.small`, CloudFront pass-through + HSTS + 30 s read timeout, RDS PG 18 `sslmode=require`, S3, SES, SSM `/campus/*`, ECR immutable SHA + keep 10, CloudWatch `/campus-hub/app`, SNS `campus-hub-alerts` `#309`, GitHub OIDC `deploy.yml` `#320`) |
| `docs/diagrams/component.puml` | Regenerated from exact cross-module imports on `main` (`545e334`): added `project` (`#140`), `PickupPassService` (`#283`), `AssetRetiredEvent` (`#280`/`#311`), `UserDisabledEvent` (`#302`/`#330`/`#340`), `LiveStreams` SSE (`#317`/`#332`/`#340`), `incident` → `StorageService` (`#210`) & `ReservationApi` (`#298`), `user` → `AuditService`, `notification` → `AsyncConfig` (`#366`) |
| `docs/diagrams/README.md` | Updated rows for `component.puml` and `deployment-aws.puml` (noting `.png` regeneration when `plantuml` is available) |
| `PROGRESS.md` | Added §11 **Reusable Template Blueprint (Post-Defense)** with 4-domain mini-table before `Everyone & Further Reading`, fixed duplicate `## 10.` numbering (`Everyone & Further Reading` → §12), and added links to the blueprint, report chapters, and runbook |
| `README.md` | Added **Template Blueprint** link to the top navigation bar |
| `documentation/Necula_Matei/74-template-blueprint-report-runbook.md` *(new)*, `02-files-explained.md`, `README.md`, `docs/whats-left.md`, `AGENTS.md` | End-of-task documentation updates strictly scoped to Matei's files and lines |

### Key facts verified against `main` (`545e334`)

- **Cross-module imports (for `component.puml`):** verified by scanning every `import com.campus.*` across `backend/src/main/java/com/campus/<module>`. Confirmed zero violations of the module boundary rules (`28` `@ArchTest` rules: 21 in `CodingConventionsArchitectureTest`, 7 in `ModuleBoundaryArchitectureTest`).
- **Endpoint & test counts (for `security.md`):** Python controller scan confirmed **71 total REST endpoints** and **29 `/api/v1/admin/**` route mappings across 11 controllers** (including `/api/v1/admin/audit-log` alias alongside `/audit-logs`); `SecurityConfigTest` has **21 `@Test` methods**, `SecurityPassIT` has **7**, `AuthFlowIT` has **6**, `FindActiveRoleFreshReadIT` has **1**.
- **Unmerged follow-ups kept strictly out of "done" claims:** auth rate limiting, self-service change-password, CloudWatch metric alarms / graceful stop, and expired-JWT MockMvc test are listed honestly as planned / in-flight follow-ups rather than merged on `main`.
- **PlantUML `.png` renders:** `plantuml` is not installed in this environment, so only `.puml` sources were updated; `.png` exports should be regenerated with `java -jar plantuml.jar -tpng docs/diagrams/component.puml docs/diagrams/deployment-aws.puml` when `plantuml` + Graphviz are available.

### How it connects to teammates' modules

- **Liviu (`catalog/`, `common/`, `storage/`, `notification/`):** documented `StorageValidation`, `LocalStorageService`, `AuditService`, `AssetRetiredEvent` (`#280`), and `LiveStreams` SSE (`#317`/`#340`/`#344`) accurately across `security.md`, `component.puml`, `aws-and-cicd.md`, and the template blueprint's Seam 1 (`lab_rooms` / `model`).
- **Calin (`reservation/`, `reassignment/`, `common/time`, `architecture/`):** documented `ReservationSlotValidator`, `frontend/src/components/reservation/bookingRules.ts`, `IdempotencyService`, `CalendarFeedController`, `SubstituteStrategy`, `MaintenanceImpactSimulator` (`#351`), `UserDisabledReservationListener` (`#330`), and all `28` ArchUnit rules across `component.puml`, `security.md`, and `reusable-template-blueprint.md`.
- **Stefania (`incident/`, `user/admin/`, `common/security`, `frontend/`, `ci.yml`):** documented `CurrentUser`, `AdminUserController` / `UserDisabledEvent` (`#206`/`#302`), incident photo upload (`#210`), `ReliabilityCalculator` (`#291`), `ci.yml` (without `hashFiles` skip guards `#303`), and frontend layout/branding seams in `reusable-template-blueprint.md`.

### Verification

| Check | Result |
|---|---|
| Cross-module import grep (`backend/src/main/java/com/campus/*`) | Matched every arrow drawn in `docs/diagrams/component.puml` |
| Controller mapping & test-count verification script on `main` `545e334` | Confirmed 71 endpoints (29 `/api/v1/admin/**` mappings), 21 tests in `SecurityConfigTest`, 28 `@ArchTest` rules, 18 Flyway migrations |
| Relative Markdown link checker across all new & edited `.md` files | Passed: 0 broken internal links |
| `git status --short` & `git diff --stat` | Docs-only diff; zero modifications to teammates' sections in `docs/whats-left.md` or `AGENTS.md` |

### Problems hit and fixes

| Problem | Fix |
|---|---|
| `PROGRESS.md` already had two `## 10.` headings (`## 10. Detailed Work Delivered per Member` at line 284 and `## 10. Everyone & Further Reading` at line 545) | Numbered the new blueprint section `## 11. Reusable Template Blueprint (Post-Defense)` and renumbered `Everyone & Further Reading` to `## 12.` so heading numbers run cleanly `1..12` |
| `docs/report/security.md` still had several 5 Nov claims ("photo upload not on main yet", "16 admin endpoints", "18 tests in SecurityConfigTest", "disabling users has no endpoint yet", "planned AWS") | Replaced every stale statement with verified facts from `main` `545e334` while keeping unmerged work explicitly labeled as planned/in-flight |
| No `start.sh` script exists on `main` for the local PostgreSQL fallback section of `docs/runbook.md` | Noted this honestly and provided exact, copy-pasteable commands for both standard local PostgreSQL 18 (`service postgresql start` + `gradlew bootRun` + `npm run dev`) and an isolated scratch PostgreSQL 18 cluster via `initdb`/`pg_ctl` |

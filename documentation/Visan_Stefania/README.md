# Documentation: Vișan Ștefania

**Member 4:** Incidents, User admin, `CurrentUser`, Frontend lead, CI
**GitHub:** [@m1runa-stefan1a](https://github.com/m1runa-stefan1a)

---

## Overview

My personal engineering notes for the **Campus Equipment & Incident Hub** (FIS course). They feed my section of the report and help me answer defense questions.

Every document has two parts:

1. **Part 1: In simple terms**: what the feature does and why, for non-programmers.
2. **Part 2: Technical details**: files, design choices, transactions, tests.

---

## Documents

| # | Document | Topic | PRs | Key concepts |
|---|---|---|---|---|
| 01 | [`01-current-user.md`](./01-current-user.md) | Who is logged in | #74 | JWT `sub` claim, `ROLE_ADMIN`, never trust ids from the body, 401 instead of 500 |
| 02 | [`02-incident-reporting.md`](./02-incident-reporting.md) | Reporting a broken item | #40 | `IncidentReport` entity, `AssetDamagedEvent` (Observer), asset goes `UNDER_MAINTENANCE`, Flyway table |
| 03 | [`03-frontend-foundation.md`](./03-frontend-foundation.md) | The React app everyone builds on | #84, #85, #86, #111 | Vite + React + TS + Mantine, `apiFetch`, `AuthProvider`, role-based routes, shared components, ADR-002 |
| 04 | [`04-frontend-register-and-auth-fixes.md`](./04-frontend-register-and-auth-fixes.md) | Register page and login robustness | #107, #142, #145 | Register + auto-login, BCrypt 72-byte limit, keep the token when the backend restarts, unique React keys |
| 05 | [`05-incident-triage-and-concurrency.md`](./05-incident-triage-and-concurrency.md) | Admin triage, My incidents, race fixes | #108 | State machine, row lock before the check, retired assets, IDOR 404, paging limits, 85 tests |
| 06 | [`06-incident-severity.md`](./06-incident-severity.md) | Only serious incidents take equipment out of use | #263 (#229) | HIGH/CRITICAL vs LOW/MEDIUM, start repair as confirmation, one "keeps out of use" rule, max 3 open reports |
| 07 | [`07-user-disabled-event.md`](./07-user-disabled-event.md) | Disabling a user frees their bookings | #302 (#229 part 2, with #266) | `UserDisabledEvent`, why not a direct call (module cycle), why the listener runs after the commit (the disable must never fail) |
| 08 | [`08-qr-libraries-and-ci-guards.md`](./08-qr-libraries-and-ci-guards.md) | QR libraries + honest CI steps | Refs #291, #269 | `qrcode.react`, `@yudiel/react-qr-scanner`, React 19 peer ranges, no `hashFiles` guards in `ci.yml` |
| 09 | [`09-admin-users-and-reassignment-log.md`](./09-admin-users-and-reassignment-log.md) | Users and Reassignment log pages | Closes #305, #306 | search + filters, confirm before disable/role change, own row locked, last-admin error from the backend, RETIRED rows without incident |
| 10 | [`10-reliability-calculator.md`](./10-reliability-calculator.md) | Which units keep breaking: the calculation | Refs #291 (PR 1 of 3) | failures per 100 h of use, 10-hour floor, compared with twins of the same model, INSPECT/WATCH, rounding only on published numbers |
| 11 | [`11-reliability-report.md`](./11-reliability-report.md) | The reliability report endpoint | Refs #291 (PR 2 of 3) | `GET /api/v1/admin/reliability`, window from the `Clock`, Calin's used minutes, one catalog lookup per unit, unknown assets skipped, `null` instead of NaN |
| 12 | [`12-equipment-health-page.md`](./12-equipment-health-page.md) | Equipment health page | Refs #291 (PR 3 of 3) | `/admin/reliability`, 90/180/365 window, level badges, `null` shown as "–", no "healthy" message on an error, horizontal scroll on a phone |
| 13 | [`13-incident-details-page.md`](./13-incident-details-page.md) | Incident details page | W7 | `/incidents/:id` for the reporter or an admin, photo inline + replace, history, admin actions, 404 for someone else's incident |
| 14 | [`14-live-toasts-mount.md`](./14-live-toasts-mount.md) | Live notifications on every page | #345 (Refs #290) | `<LiveToasts />` mounted once in `AppLayout`, stream opens after login and closes on logout, StrictMode double mount, why not in `App.tsx` |
| 16 | [`16-frontend-audit-fixes.md`](./16-frontend-audit-fixes.md) | Frontend audit fixes | #369 (Refs #364) | incident details reset with a `key`, computed loading on Users, `apiFetch` extra headers with `Headers`, 13 error texts, failed auto-login → Login page |

---

## What I own

| Area | Where | Notes |
|---|---|---|
| Incidents (backend) | `backend/src/main/java/com/campus/incident/` | report, triage, my incidents, photo (next) |
| Current user | `backend/src/main/java/com/campus/common/security/CurrentUser.java` | used by every module |
| User admin | `backend/src/main/java/com/campus/user/admin/` | planned |
| Frontend (lead) | `frontend/` | shared parts, my pages, review of every frontend PR |
| CI | `.github/workflows/` | `npm ci`, `npm run lint`, `npm run build` for the frontend |

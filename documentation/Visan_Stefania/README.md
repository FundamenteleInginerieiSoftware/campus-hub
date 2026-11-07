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

---

## What I own

| Area | Where | Notes |
|---|---|---|
| Incidents (backend) | `backend/src/main/java/com/campus/incident/` | report, triage, my incidents, photo (next) |
| Current user | `backend/src/main/java/com/campus/common/security/CurrentUser.java` | used by every module |
| User admin | `backend/src/main/java/com/campus/user/admin/` | planned |
| Frontend (lead) | `frontend/` | shared parts, my pages, review of every frontend PR |
| CI | `.github/workflows/` | `npm ci`, `npm run lint`, `npm run build` for the frontend |

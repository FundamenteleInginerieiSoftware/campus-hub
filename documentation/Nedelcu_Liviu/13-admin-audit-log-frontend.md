# 13 — Admin Audit Log Frontend Page (`/admin/audit-logs`)

**Author:** Liviu Nedelcu (`@NliviuN`)  
**Date:** 2026-11-08 (W6)

---

## Part 1: In simple terms (for non-programmers)

Every time an administrator adds or retires lab equipment, a student books or returns a device, an incident is triaged, or an account role is updated, the backend records an entry in the `audit_logs` table. Previously, those records could only be queried through the backend REST API (`GET /api/v1/admin/audit-logs`, #217).

This change adds the **Audit log** page in the web interface under the administrator **Operations** menu (`/admin/audit-logs`). Administrators can:
1. Browse all system actions in reverse chronological order (newest first), 20 entries per page.
2. Filter the log by **Entity type** (`ASSET`, `CATEGORY`, `LAB_ROOM`, `Reservation`, `INCIDENT`, `USER`), **Action** (grouped by module), **Actor ID**, **Entity ID**, and **Date range** (`From date` / `To date`).
3. Click directly on any `ASSET #<id>` entry to open that equipment's details page, or distinguish human actions (`User #<id>`) from automated background jobs (`System job`, e.g. `AUTO_NO_SHOW` and `MARK_OVERDUE`).

---

## Part 2: Technical details

### Files added & modified

| File | Purpose |
|---|---|
| `frontend/src/api/audit.ts` | Typed client `searchAuditLogs(params)` calling `GET /api/v1/admin/audit-logs` via `apiFetch<PageResponse<AuditLogEntry>>`. |
| `frontend/src/pages/AdminAuditLogPage.tsx` | Administrator audit trail page using `PageHeader`, `ErrorAlert`, Mantine filter controls with 300ms `useDebouncedValue` on ID inputs, and `PagedTable`. |
| `frontend/src/App.tsx` | Registers `/admin/audit-logs` (and `/admin/audit-log` redirect alias) under `<RequireAdmin />`. |
| `frontend/src/layout/navigation.ts` | Adds `Audit log` (`IconHistory`) to the administrator `Operations` menu. |
| `docs/modules/frontend.md` | Documents `/admin/audit-logs` in the route table. |

### Key implementation details

- **Campus local timestamps without timezone shift:** The backend serializes `LocalDateTime` values without offset (`"2026-11-08T14:05:19"` in `Europe/Bucharest`). `formatTimestamp` slices the ISO string directly instead of passing it through `new Date()`, avoiding browser timezone shifts. Similarly, `fromDate` and `toDate` inputs send `YYYY-MM-DDT00:00:00` and `YYYY-MM-DDT23:59:59` rather than `toISOString()`.
- **No-op state guards:** Select, date, reset, and pagination handlers check whether the selected value actually changed before calling `setLoading(true)`, preventing permanent loading spinners when clicking the active page or resetting already-empty filters.
- **Debounced numeric filters:** `actorIdInput` and `entityIdInput` are debounced by 300ms via `useDebouncedValue` so typing multi-digit IDs does not trigger redundant requests on every keystroke.

# Users and Reassignment Log Pages (Vișan Ștefania)

**Role:** Member 4 (frontend lead)
**Date:** 2026-11-20
**Package:** `frontend/src/pages/AdminUsersPage.tsx`, `frontend/src/pages/ReassignmentLogPage.tsx`
**Issues:** #305 (admin users page, mandatory), #306 (reassignment log page)

---

## Part 1: In simple terms

**Users (Admin → Users):** the admin finds an account by email or name, filters by role (student/admin) and status (active/disabled), and can:

- **Make admin / Make student**: change the role. It counts from that person's next click.
- **Disable**: the person can't log in any more, the session they have open stops working at once, and their upcoming bookings are cancelled (#302). Things they already picked up stay with them until they bring them back.
- **Enable**: the account works again.

Your own row has its buttons greyed out: you can't disable yourself or remove your own admin role. And the last active admin can never be disabled or demoted: the app shows an error instead. On AWS there is no database console for us, so this page is **the only way** to make someone an admin or block an account.

**Reassignment log (Admin → Reassignment log):** a history of what the app did on its own when equipment broke (a High/Critical report, or a repair started) or was retired. One row per booking: **Moved** to a free unit of the same model or category, or **Cancelled** when nothing was free. It shows when, why (which incident, or "Asset retired"), which booking and which equipment.

---

## Part 2: Technical details

### Users page

| Piece | What it does |
|---|---|
| `api/adminUsers.ts` | `searchAdminUsers` (`GET /api/v1/admin/users?q&role&enabled&page&size`), `disableUser`, `enableUser`, `changeUserRole` (`PUT .../role`), next to the existing `fetchAdminUser` |
| Search box | 300 ms debounce, max 100 characters (the backend refuses longer, #206) |
| Filters | a new filter or search starts from page 0 |
| Confirmation | Disable and role changes open a modal that says what will happen; Enable is direct (it only gives access back) |
| Own row | buttons disabled with a tooltip, using `useAuth().user.id`; the backend still refuses it (409 `CANNOT_MODIFY_SELF`) |
| Last admin | can't be known from one page of results, so the backend decides: 409 `LAST_ADMIN_REQUIRED` is shown through `ErrorAlert` and `errorMessages.ts` |
| After an action | green notice + reload of the current page, so the table shows the new role/status |

### Reassignment log page

| Piece | What it does |
|---|---|
| `api/reassignments.ts` | type `ReassignmentLog` = `ReassignmentLogResponse.java` (Calin), `fetchReassignmentLogs` (`GET /api/v1/admin/reassignments`) |
| Why | `trigger = INCIDENT` → link "Incident #N" to the queue; `trigger = RETIRED` (`incidentId = null`, #311) → "Asset retired" |
| Equipment | from → to; asset names looked up with `fetchAssetSummary` and cached in a `useRef`, the same pattern as the incident queue (the log only has ids, module boundaries) |
| Matched by | `SameModelStrategy` → "Same model", `SameCategoryStrategy` → "Same category"; unknown names are shown as they come |

Both pages use the shared `PagedTable`, `PageHeader` and `ErrorAlert`, and guard `onPageChange` against the current page (#133, #196). The routes are `/admin/users` (new, plus a "Users" menu entry) and `/admin/reassignments` (was a placeholder). `PlaceholderPage` is no longer used by any route.

### Checks

`npm run lint` (0 warnings) and `npm run build`; clicked through both pages against a stubbed API: own row disabled, disable with confirmation, role change, search, a RETIRED and an INCIDENT log row.

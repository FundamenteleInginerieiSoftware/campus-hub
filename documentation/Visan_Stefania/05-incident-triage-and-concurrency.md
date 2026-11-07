# Admin Triage, My Incidents and the Race Fixes (Vișan Ștefania)

**Role:** Member 4
**Date:** 2026-11-06
**Package:** `com.campus.incident`
**PR:** #108 (closes #146, #164; part of #159, #189)

---

## Part 1: In simple terms

**New actions for the admin:**

| Action | Meaning | The item |
|---|---|---|
| **Start repair** | "I took it to be repaired" (OPEN → IN_REPAIR) | stays out of use |
| **Resolve** | "it is fixed" | usable again, if no other report is still open on it |
| **Reject** | "the report was wrong, it works" | usable again, same rule |

**New pages' data for students:** "My incidents" lists only the reports I made, newest first. Opening one incident shows it only to the person who reported it or to an admin. Anyone else gets "not found", exactly as for an id that does not exist, so nobody can even learn which reports exist.

**Two real bugs fixed (found by Matei and Calin):**

1. **Two people at the same moment.** If an admin closed the last report on an item while a student reported a new problem on the same item, the item could end up "available" with an open report, so a broken item could be booked. Now the item is **locked** while either action runs: the second one waits for the first to finish, then decides with the right information.
2. **A retired item.** If the admin retired a broken item (thrown away) while its report was open, the report could never be closed, because the app tried to make a retired item "available" and failed. Now a retired item simply stays retired, and the report closes normally.

---

## Part 2: Technical details

### Endpoints

| Endpoint | Who | Result | Errors |
|---|---|---|---|
| `POST /api/v1/admin/incidents/{id}/start-repair` | admin | `IN_REPAIR`; twice → unchanged | 404 `INCIDENT_NOT_FOUND`, 409 `INVALID_INCIDENT_STATUS_TRANSITION` |
| `POST /api/v1/admin/incidents/{id}/reject` | admin | `REJECTED`; optional `{"resolutionNotes"}` | 400, 404, 409 |
| `GET /api/v1/incidents/me?page&size` | logged in | `PageResponse<IncidentResponse>`, my reports | 400 `INVALID_PARAMETER` |
| `GET /api/v1/incidents/{id}` | reporter or admin | one incident | 404 for anyone else |

### State machine (`IncidentReport`)

```
OPEN ──start-repair──▶ IN_REPAIR
 │                       │
 ├── resolve / reject ───┤
 ▼                       ▼
RESOLVED            REJECTED      (both final)
```

| Method | Allowed from | Sets |
|---|---|---|
| `startRepair()` | `OPEN` | status |
| `resolve(adminId, notes, at)` | `OPEN`, `IN_REPAIR` | status, `resolvedById`, `resolutionNotes`, `resolvedAt` |
| `reject(adminId, notes, at)` | `OPEN`, `IN_REPAIR` | same fields: they always say who closed it, when and why |

Anything else throws `InvalidStateTransitionException` → 409. The service returns the incident unchanged when it is already in the target state (idempotent).

### Bug 1: the race (Matei's review, #164)

Under PostgreSQL's default isolation (READ COMMITTED) a query does not see rows another transaction has not committed yet.

| Time | Admin: resolve the last incident | Student: report a new one |
|---|---|---|
| t1 | saves `RESOLVED` | |
| t2 | | inserts the new `OPEN` incident (not committed) |
| t3 | "any active incident?" → **no** (cannot see t2) → `markAvailable` | |
| t4 | commit | `markUnderMaintenance`, commit |

Depending on timing the asset ended up AVAILABLE with an OPEN incident, or UNDER_MAINTENANCE with none (Matei reproduced it in 24 and 20 of 25 tries).

**Fix: both sides take the same row lock first** (`SELECT ... FOR UPDATE` on the asset):

| Use case | New order |
|---|---|
| `reportIncident` | 1. `markUnderMaintenance(assetId)` (locks; 404/409) → 2. save → 3. publish event |
| `releaseAssetIfNoActiveIncident` (after resolve/reject) | 1. `lockForBooking(assetId)` → 2. `existsByAssetIdAndStatusIn(asset, [OPEN, IN_REPAIR])` → 3. `markAvailable` only if none and the asset is still `UNDER_MAINTENANCE` |

The second transaction waits at the lock until the first commits; its next query then sees the committed result. Side effects: a report on a retired asset no longer inserts a row (no id burnt), and `getAsset` before the report is no longer needed.

### Bug 2: retired asset (#146)

`markAvailable` on a `RETIRED` asset throws 409 `ASSET_RETIRED`, which rolled back the whole resolve. Now `catalogApi.getAsset(id).status() == UNDER_MAINTENANCE` is checked first; RETIRED and AVAILABLE are left alone.

### Paging (#146, #164, Matei's review)

| Before | After |
|---|---|
| raw Spring `Pageable`: `?size=500` returned 500 rows; `?page=999999999` → offset over `int` → **500** | plain `int page, int size`; `pageRequest()` throws 400 `INVALID_PARAMETER` for `page < 0`, `size` outside 1–100, `page * size > Integer.MAX_VALUE` |
| sort by `createdAt` only (whole seconds → random order inside a second) | `createdAt DESC, id DESC` |
| client could pass `sort=anything` → 500 | the sort is fixed on the server |

### Other changes

- `existsByAssetIdAndStatusIn` instead of loading whole rows (with `TEXT` columns) just to call `isEmpty()` (#159 point 1).
- `@Transactional` on each method, `readOnly = true` on reads (team rule, ArchUnit #179).
- IDOR: `getIncident` throws the same `INCIDENT_NOT_FOUND` for "not yours" and "does not exist".

### Tests: 8 → 85 methods

| Class | Kind | # | Covers |
|---|---|---|---|
| `IncidentServiceTest` | unit, Mockito, `@Nested` per use case | 31 | every transition, idempotency, `InOrder` lock-before-query and lock-before-insert, retired/available asset, paging limits (parameterized), ownership 404 |
| `IncidentReportTest` (new) | unit, no Spring | 8 | the state machine: all allowed and forbidden transitions (parameterized) |
| `IncidentControllerTest` | `@WebMvcTest` slice | 14 | 201/400/401/404/409, page params, text id → 400 |
| `AdminIncidentControllerTest` | `@WebMvcTest` slice | 20 | 401/403 for every admin action, status filter, size > 100, bodies with/without notes, 2000-char limit |
| `IncidentFlowIT` (new) | `@SpringBootTest`, real DB, real login | 12 | report → start-repair → resolve with the asset's real status after each step; two incidents on one asset; retired asset; report on retired → 409 and nothing saved; my incidents newest first; another student → 404; huge page → 400 |

Full backend build: 564 tests green locally.

### Defense questions

- **Why a pessimistic lock and not only `@Version`?** `@Version` protects one row from lost updates. Here the decision ("is any incident still active?") reads *other* rows; only a lock that both sides take serializes the two transactions.
- **Why lock the asset and not the incidents?** The asset row exists in both use cases; a new incident row does not exist yet when the other side checks.
- **Can this deadlock?** Every path takes the asset lock once, before or after touching only its own incident row, so there is no lock cycle between two incidents of the same asset.

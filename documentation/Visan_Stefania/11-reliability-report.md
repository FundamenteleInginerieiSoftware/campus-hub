# The Reliability Report Endpoint (Vișan Ștefania)

**Role:** Member 4
**Date:** 2026-11-20
**Package:** `com.campus.incident`
**PR:** PR 2 of 3 for equipment reliability (Refs #291); builds on PR 1 (`ReliabilityCalculator`, doc 10)

---

## Part 1: In simple terms

PR 1 taught the app **how** to decide which units keep breaking. This PR gives it the **data** and a door for the admin:

`GET /api/v1/admin/reliability?days=180` → the list of units that failed in the last 180 days, worst first, each with its numbers and one sentence ("2 incidents in the last 180 days; fails 6.0× more often than other DS1054Z units").

Where the data comes from:

| What | From whom |
|---|---|
| incidents in the window | my own `incident_reports` table |
| hours each unit was really out (check-out → return) | Calin's `ReservationApi.usedMinutesByAsset` (#298) |
| name, model and status of each unit | Liviu's `CatalogApi.getAsset` |

Nothing is stored: every call adds things up again from live data. Only admins can open it; a student gets 403. PR 3 is the page that shows it.

---

## Part 2: Technical details

### Files

| File | What |
|---|---|
| `incident/repository/IncidentReportRepository.java` | `findByCreatedAtGreaterThanEqual(since)`: incidents created at or after the window start, any status |
| `incident/service/ReliabilityService.java` | `report(days)`: window from the `Clock`, reads the three sources, maps them to `UnitInput` / `IncidentInput`, calls `ReliabilityCalculator.rank` |
| `incident/web/ReliabilityReportResponse.java` | `windowDays`, `from`, `to`, `items` |
| `incident/web/AdminReliabilityController.java` | `GET /api/v1/admin/reliability`, `@Min(30) @Max(365)`, default 180 |
| `docs/api-contract.md` | new section "Equipment reliability (Stefania)" |

### How `report(days)` works

1. `to` = now from the shared `Clock`, cut to seconds like the rest of the API; `from = to − days`.
2. Incidents with `createdAt ≥ from` (my repository) and Calin's minutes for `[from, to]`.
3. The units = every asset id that appears in **either** list. A unit that was used but never failed is not listed, but it must be there: it is a twin the lemon is compared with.
4. One `CatalogApi.getAsset` per unit. An asset the catalog no longer knows (`NotFoundException`) is skipped, so one missing asset never breaks the whole page.
5. Mapping: `rejected` = status `REJECTED`; `severe` = `HIGH`/`CRITICAL` (the same `takesAssetOutOfUseAtOnce()` rule as #263); `resolvedAt` only for `RESOLVED` (a rejected report also has a `resolvedAt`, but that is not a repair time).

### Module boundaries

`incident → reservation.api` is new, but already allowed: AGENTS.md §5 ("reservation.api ← reassignment (optionally incident)") and ArchUnit rule 3 only forbid `reservation → incident`. No new module, no ArchUnit change, no `SecurityConfig` change (`/api/v1/admin/**` is admin-only).

### Accepted limit

One catalog lookup per unit (spec §8). For a lab with tens of units that is fine; a bulk read on `CatalogApi` (Liviu) would make it one query.

### Tests

- `ReliabilityServiceTest` (Mockito, fixed `Clock` 10 Nov 2026 10:00): the window is `now − days … now` for both sources; incidents and minutes reach the calculator (600 min → 10.0 h); `REJECTED` is not a failure; `HIGH`/`CRITICAL` are severe; only `RESOLVED` has a repair time; a unit used without incidents counts for its twin's ratio (and is asked from the catalog) but is not listed; an unknown asset is skipped; the catalog is asked once per unit even with 3 incidents; no data → empty report.
- `AdminReliabilityControllerTest` (`@WebMvcTest`): 401 anonymous; 403 student; default `days` 180; `days=29` and `days=366` → 400 `VALIDATION_FAILED`; the JSON shape of a ranked row; `vsModel`, `mtbfHours`, `avgRepairHours` are present as JSON `null`.

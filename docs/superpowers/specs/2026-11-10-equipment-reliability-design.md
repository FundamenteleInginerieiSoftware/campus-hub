# Equipment reliability ("which units keep breaking?"): design

**Status:** proposed, 2026-11-10 (written by Matei; Stefania confirms at the next meeting). **Owner:** Stefania (inside her `incident` module). **Needs from teammates:** one read method from Calin.

**Split of the four new features (10 Nov):** each goes to the member who owns the data it reads: pickup pass → Matei, calendar feed → Calin, live notifications → Liviu, equipment reliability → Stefania (half the input is her incidents, and the page is an admin page like her incident queue). Proposed until the team agrees.
**Deadline:** before the feature freeze (Mon 7 Dec, W10). Must not slow down M1 (15 Nov) or the W7 deploy.
**Priority:** the first of the four to drop if time runs out (cut line in §10).

## 1. Goal

The app records every incident and every booking, but nobody adds them up. An admin can't answer "which oscilloscope should we send for inspection?" without reading the incident list by hand.

The **Equipment health** page (admin) lists the units that had incidents, worst first, and says in one sentence why:

> **Oscilloscope DS1054Z #2** · INSPECT · 3 incidents in the last 180 days; fails 2.4× more often than other DS1054Z units

Behind it are four numbers per unit: incidents, hours of real use, incidents per 100 hours of use, and how that compares with the other units of the same model.

Success = in the demo the page ranks the seeded "bad" unit first with the right sentence, and each rule of the calculation has a unit test with the numbers worked out by hand.

How this differs from Calin's analytics (#255, #256): his pages answer "how much is the equipment **used**" (demand, heat map, capacity). This page answers "how often does it **fail**". It uses incidents, which his do not.

## 2. Decisions (proposed, Stefania confirms)

| # | Decision | Why |
|---|---|---|
| 1 | A failure = an incident report that was **not rejected** (`OPEN`, `IN_REPAIR`, `RESOLVED`) | A rejected report means the unit worked |
| 2 | Failures are measured **per hours of real use**, not just counted | A unit lent 200 hours with 2 incidents is better than one lent 10 hours with 2 |
| 3 | Below **10 hours** of use we calculate as if it were 10 | One incident after 1 hour would otherwise read as "100 incidents per 100 hours" |
| 4 | A unit is compared with the **other units of the same model** | Some models are simply fragile; a lemon is a unit that is worse than its twins |
| 5 | Three levels with fixed, written thresholds (§5.3); no hidden score | An admin and the professor can check any row with a calculator |
| 6 | Calculated on request from live data; nothing stored | No table, no migration, no nightly job; at lab scale (tens of units) it is a few queries |
| 7 | Admin only, read only. The page links to the unit; retiring stays in Liviu's inventory | We show the evidence; the decision is a human's |
| 8 | The calculation is one pure class (`ReliabilityCalculator`): lists in, list out | Testable without Spring, a database or mocks |

## 3. Ownership: what we build vs. what we ask for

| Who | What |
|---|---|
| Stefania | In `incident/`: `ReliabilityCalculator`, `ReliabilityService`, one query on her own `IncidentReportRepository` (incidents created since a time), `AdminReliabilityController` + records; `frontend/src/api/reliability.ts`, `AdminReliabilityPage.tsx`, route `admin/reliability` + menu entry "Equipment health" (her files); api-contract section; `docs/modules/incident.md`; personal docs |
| Calin | A read method on `ReservationApi`: minutes each asset was really out (check-out → return, or → now if still out) for check-outs in a time range (proposed: `usedMinutesByAsset(LocalDateTime from, LocalDateTime to)` → `Map<Long, Long>`). His analytics work (#255) already computes something close |
| Matei | Nothing (`/api/v1/admin/**` is already admin-only) |
| Liviu | Nothing required (`CatalogApi.getAsset` exists). Nice to have later: a bulk lookup, so the page needs one catalog query instead of one per unit |
| Calin + Stefania (dev seed, nice to have) | A few returned bookings in the past and ~6 incidents on 3 units in their dev seeders, so the page has something convincing to show on a fresh database |

## 4. Architecture

```
com.campus.incident (Stefania, existing module)
  repository/ IncidentReportRepository  + one query: incidents created since a time
  service/  ReliabilityCalculator   pure: units + incidents + usage -> ranked rows. No Spring, no clock.
            ReliabilityService      window from the Clock; reads her repository, Calin's ReservationApi and CatalogApi; calls the calculator
  web/      AdminReliabilityController   GET /api/v1/admin/reliability
            ReliabilityReportResponse, AssetReliabilityItem, ReliabilityLevel
```

- **Dependencies:** `incident → catalog.api` (already there) and now also `incident → reservation.api`. AGENTS.md §5 already allows it ("reservation.api <- reassignment (optionally incident)") and Calin's ArchUnit rule 3 only forbids the other direction, so: no new module, no `incident.api` facade, no ArchUnit change.
- The calculator has its **own** small input records (`UnitInput`, `IncidentInput`), so it can be written and fully tested before Calin's method exists; the service maps the entity and his types to these.
- "Now" from the shared `Clock`.

## 5. The calculation

### 5.1 Inputs, for a window of `days` (default 180) ending now

- **Incidents** created in the window (Stefania's own repository).
- **Used minutes** per asset in the window (Calin's `ReservationApi`).
- **Units** = every asset id that appears in either list, looked up with `CatalogApi.getAsset` (name, model, status). An id the catalog no longer knows is skipped.

### 5.2 Numbers per unit

| Number | Formula |
|---|---|
| `failures` | incidents on the unit whose status is not `REJECTED` |
| `severeFailures` | those with severity `HIGH` or `CRITICAL` |
| `usageHours` | used minutes / 60, one decimal |
| `failuresPer100h` | `failures / max(usageHours, 10) × 100`, one decimal |
| `mtbfHours` (mean hours of use between failures) | `usageHours / failures`, one decimal; empty when `failures = 0` or `usageHours = 0` |
| `avgRepairHours` | average of (resolved − created) over the unit's `RESOLVED` incidents, one decimal; empty when there are none |
| `vsModel` | the unit's rate ÷ the rate of the **other** units of the same model taken together: `othersRate = othersFailures / max(othersHours, 10) × 100`. Empty when there is no other unit of that model with at least 10 hours of use, or when `othersRate = 0` |

### 5.3 Level

| Level | Rule |
|---|---|
| `INSPECT` | `failures ≥ 3`, **or** `failures ≥ 2` and `vsModel ≥ 2.0` |
| `WATCH` | `failures ≥ 1` and not `INSPECT` |
| (not listed) | `failures = 0` |

Constants together at the top of `ReliabilityCalculator`, each with a one-line *why*: `MIN_HOURS = 10`, `INSPECT_FAILURES = 3`, `LEMON_FAILURES = 2`, `LEMON_RATIO = 2.0`.

### 5.4 The sentence (`reason`, built by the backend)

`"<failures> incident(s) in the last <days> days"`, then, only when `vsModel ≥ 2.0`: `"; fails <vsModel, one decimal>× more often than other <model> units"`. Singular "incident" for 1.

### 5.5 Order

`INSPECT` before `WATCH`; inside a level by `failuresPer100h` descending; ties by asset id ascending (the same order on every run).

### 5.6 Worked example (also the first test)

Model DS1054Z, 180 days. Unit #2: 3 incidents (one rejected → **2 failures**), 40 h used → `2 / 40 × 100 = 5.0` per 100 h. Units #1 and #3 together: 1 failure, 120 h → `1 / 120 × 100 = 0.83`. `vsModel = 5.0 / 0.83 = 6.0`. Level: 2 failures and ratio ≥ 2.0 → `INSPECT`. Sentence: "2 incidents in the last 180 days; fails 6.0× more often than other DS1054Z units".

## 6. API (`docs/api-contract.md` gets an "Equipment reliability (Stefania)" section)

### `GET /api/v1/admin/reliability?days=180` (ADMIN)

`days` from 30 to 365, default 180; outside → `400 VALIDATION_FAILED`.

`200` → `ReliabilityReportResponse { windowDays, from, to, items: AssetReliabilityItem[] }`, with
`AssetReliabilityItem { assetId, assetName, model, status, failures, severeFailures, usageHours, failuresPer100h, mtbfHours, avgRepairHours, vsModel, level, reason }` (`mtbfHours`, `avgRepairHours`, `vsModel` can be `null`).

Not paged: only units with at least one failure are listed (documented). No new error code.

## 7. Frontend (Stefania's page)

- `src/api/reliability.ts`: `fetchReliability(days)`.
- `AdminReliabilityPage.tsx` (`/admin/reliability`, menu "Equipment health" in the admin "Equipment" group):
  - window select: 90 / 180 / 365 days;
  - table: unit (links to `/catalog/:id`), model, status badge, level badge (`INSPECT` red, `WATCH` yellow), failures (severe in brackets), hours used, per 100 h, vs. model ("6.0×" or "–"), the sentence;
  - "How is this calculated?" opens a short text with the rules of §5.3 and the 10-hour rule;
  - empty state: "No incidents in this period. Every unit is healthy."
- Every load resets its error and stops its spinner in `finally`.

## 8. Limits we accept (written in the personal doc and the report)

- One catalog lookup per unit that had use or incidents. Fine for a lab (tens to a few hundred units); a bulk method from Liviu would remove it.
- With little history the numbers are rough; the 10-hour floor and the "at least 2 failures" rule for the ratio keep one unlucky incident from marking a unit `INSPECT`.
- A unit whose twins never failed has no `vsModel` (division by zero); it reaches `INSPECT` only through the count rule.
- Use is counted from check-out to return; time a unit was booked but never picked up does not count.

## 9. Testing (AGENTS.md §5)

| Test | Kind | Covers |
|---|---|---|
| `ReliabilityCalculatorTest` | plain JUnit | the worked example of §5.6; each rule and each boundary of §5.2–5.5 (list in the plan) |
| `ReliabilityServiceTest` | Mockito (`IncidentReportRepository`, `ReservationApi`, `CatalogApi`), fixed `Clock` | window = now − `days` … now; mapping of the entity and Calin's types; unknown asset skipped; one catalog call per unit |
| `AdminReliabilityControllerTest` | `@WebMvcTest` | 401; student 403; `days=29` and `days=366` → 400; default 180; 200 shape with `null`s |

Frontend: `npm run lint && npm run build`, screenshots in the PR.

## 10. Delivery (one PR each, one personal doc each)

| PR | Branch | Content | Waits on |
|---|---|---|---|
| 1 | `feat/incident-reliability-calculator` | `ReliabilityCalculator` + its records + tests | – |
| 2 | `feat/incident-reliability-report` | repository query, `ReliabilityService`, endpoint, api-contract, `docs/modules/incident.md` | PR 1; Calin's method on `main` |
| 3 | `feat/frontend-reliability` | `reliability.ts`, `AdminReliabilityPage.tsx`, route + menu | PR 2 |

**Cut line:** if Calin's method is not on `main` by 30 Nov, stop after PR 1 and describe the calculation in the report as future work. Do not build a private copy of the queries.

## 11. Out of scope (YAGNI)

A health badge for students on the asset page, automatic retire or automatic maintenance bookings, notifications to admins when a unit becomes `INSPECT`, weighting by severity, trends over time, stored snapshots, export.

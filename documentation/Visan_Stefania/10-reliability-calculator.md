# Which Units Keep Breaking: the Calculation (Vișan Ștefania)

**Role:** Member 4
**Date:** 2026-11-20
**Package:** `com.campus.incident`
**PR:** PR 1 of 3 for equipment reliability (Refs #291; spec and plan in `docs/superpowers/*/2026-11-10-equipment-reliability*`)

---

## Part 1: In simple terms

**The question:** "which oscilloscope should we send for inspection?" The app has every incident and every booking, but nobody adds them up.

**The idea:** don't just count incidents. A unit lent for 200 hours with 2 incidents is fine; one lent for 10 hours with 2 incidents is not. So we count **incidents per 100 hours of real use**, and we compare each unit with its **twins** (the other units of the same model). Some models are simply fragile; a lemon is a unit that is worse than its own twins.

**The story of the spec example:** the lab has three DS1054Z oscilloscopes. In the last 180 days:

| Unit | Used | Incidents | Per 100 h |
|---|---|---|---|
| #1 | 60 h | 1 | 1.7 |
| #2 | 40 h | 3, but the admin rejected one → **2 failures** | **5.0** |
| #3 | 60 h | 0 | 0 |

The twins of #2 (#1 and #3 together) had 1 failure in 120 h, that is 0.83 per 100 h. #2 fails 5.0 / 0.83 = **6.0 times** more often. With 2 failures and a ratio of at least 2, it is marked **INSPECT**:

> 2 incidents in the last 180 days; fails 6.0× more often than other DS1054Z units

#1 is **WATCH** (one failure), #3 is not listed (it never failed).

This PR is only the calculation. PR 2 feeds it real data (incidents, Calin's used minutes, the catalog) behind `GET /api/v1/admin/reliability`; PR 3 is the Equipment health page.

---

## Part 2: Technical details

### Files

| File | What |
|---|---|
| `incident/service/ReliabilityCalculator.java` | `rank(units, incidents, windowDays)`: pure, static, no Spring, no clock |
| `incident/service/UnitInput.java` | one unit: id, name, model, status, used minutes |
| `incident/service/IncidentInput.java` | one incident: asset, rejected?, severe?, created, resolved (only for `RESOLVED`) |
| `incident/web/AssetReliabilityItem.java` | one published row (PR 2 returns it as JSON) |
| `incident/web/ReliabilityLevel.java` | `INSPECT`, `WATCH` |

The calculator has its own input records, so it was written and tested before the service exists; PR 2 maps the `IncidentReport` entity and Calin's `Map<Long, Long>` to them.

### Formulas (spec §5.2)

| Number | Formula |
|---|---|
| `failures` | incidents not `REJECTED` |
| `severeFailures` | those with severity `HIGH`/`CRITICAL` |
| `usageHours` | used minutes / 60 |
| `failuresPer100h` | `failures × 100 / max(usageHours, 10)` |
| `mtbfHours` | `usageHours / failures`; `null` when the unit was never used |
| `avgRepairHours` | average of resolved − created over the `RESOLVED` incidents; `null` when there are none |
| `vsModel` | unit rate ÷ rate of the **other** units of the model together; `null` when they have less than 10 h together or never failed |

### Constants and why

| Constant | Value | Why |
|---|---|---|
| `MIN_HOURS` | 10 | one incident after 1 hour of use must not read as "100 per 100 h" |
| `INSPECT_FAILURES` | 3 | three real failures are enough on their own |
| `LEMON_FAILURES` | 2 | the ratio rule needs 2 failures, so one unlucky incident never makes INSPECT |
| `LEMON_RATIO` | 2.0 | a lemon fails at least twice as often as its twins |

**Level:** `INSPECT` when failures ≥ 3, or failures ≥ 2 and `vsModel` ≥ 2.0; otherwise `WATCH`. **Order:** INSPECT first, then the rate (highest first), then the asset id, so every run gives the same order.

**Rounding:** one decimal, `HALF_UP`, only on the published numbers. Levels, the ratio check and the sort use the unrounded values: a ratio of 1.98 is published as `2.0` but stays `WATCH`.

**The unit is left out of its own baseline:** otherwise a lemon would pull its model's average up and hide inside it.

### Tests (`ReliabilityCalculatorTest`, plain JUnit, numbers worked out in a comment above each assertion)

The spec's worked example; only rejected reports → not listed; 3 failures → INSPECT without any ratio; 2 failures without a ratio → WATCH; ratio exactly 2.0 → INSPECT; ratio 1.98 → WATCH and no "fails …" part; 1 failure with a ratio of 100 → still WATCH; 60 minutes of use → rate on 10 h; zero use → floor and no MTBF; MTBF; average repair over resolved only (and `null` with none); severe counted separately; no twins / twins without failures / twins under 10 h together → no ratio; comparison per model; a retired unit is listed and counts for its twins; sort by level, then rate, then id; singular sentence; one decimal; incidents on units the catalog didn't return are ignored.

### Limits (spec §8)

With little history the numbers are rough; the 10-hour floor and the 2-failure rule keep one incident from marking a unit INSPECT. A unit whose twins never failed has no ratio and reaches INSPECT only through the count rule. Use is counted from check-out to return (Calin's `usedMinutesByAsset`, #298); a booking never picked up does not count.

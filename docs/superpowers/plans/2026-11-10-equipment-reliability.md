# Equipment reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An admin page that ranks the units that keep breaking, with the numbers and one sentence of evidence per unit.

**Owner:** Stefania (split of 10 Nov: each new feature goes to the member who owns its data; proposed until the team agrees). This plan is a guide: she writes the code.

**Architecture:** Inside Stefania's `incident` module, no new module. `ReliabilityCalculator` is a pure class with its own input records: units, incidents and used minutes in, ranked rows out. `ReliabilityService` reads the window from the shared `Clock`, fetches incidents (her own `IncidentReportRepository`), used minutes (Calin's `ReservationApi`) and unit details (Liviu's `CatalogApi`), and calls the calculator. One admin GET endpoint and one admin page. No table, no migration, no `SecurityConfig` change.

**Tech Stack:** Spring Boot 4.1.1, Java 25, `BigDecimal` for one-decimal rounding, JUnit 5 + AssertJ + Mockito, `@WebMvcTest`; React 19 + Mantine 9 + TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-10-equipment-reliability-design.md`

## Global Constraints

- Never edit a teammate's file (AGENTS.md §3) and never write a private copy of their queries. Task 2 starts only when Calin's used-minutes read is on `origin/main`; use the name and types **he** shipped.
- The new classes import from other modules only `com.campus.reservation.api.*`, `com.campus.catalog.api.*`, `com.campus.common.*` (`incident → reservation.api` is already allowed by AGENTS.md §5 and ArchUnit rule 3). Packages `repository`, `service`, `web` of `incident`.
- Constants exactly: `MIN_HOURS = 10`, `INSPECT_FAILURES = 3`, `LEMON_FAILURES = 2`, `LEMON_RATIO = 2.0`; `days` default 180, allowed 30..365.
- Rounding: one decimal, `RoundingMode.HALF_UP`, applied to each **published** number; levels and ratios are decided on the unrounded values.
- A failure = incident whose status is not `REJECTED`; severe = severity `HIGH` or `CRITICAL`.
- Sentence exactly: `"<n> incident in the last <days> days"` for 1, `"<n> incidents in the last <days> days"` otherwise; when `vsModel ≥ 2.0` append `"; fails <x.x>× more often than other <model> units"`.
- Order: `INSPECT` before `WATCH`; then `failuresPer100h` descending; then `assetId` ascending.
- Endpoint under `/api/v1/admin/**` (ADMIN by `SecurityConfig`). DTOs are `record`s in `web/`. `@Transactional(readOnly = true)` on the public service method.
- "Now" only from the injected `Clock`. Tests: one rule per test, `.as("<rule>; see <Class.method>")`, fixed `Clock`, numbers worked out in a comment above each assertion.
- Comments only for hidden behaviour, *why*, links to teammates' code. Commits with your own git identity, **no AI trailer**. Each PR ships its personal doc + `02-files-explained.md` + docs `README.md` rows.
- Before each push: `cd backend && ./gradlew spotlessApply build` → `BUILD SUCCESSFUL`; frontend `npm run lint && npm run build`.

## Review Focus

1. **A unit with incidents but zero recorded use** (reported broken before anyone checked it out) → listed, rate calculated on the 10-hour floor, `mtbfHours` empty, no division by zero. Test: `ReliabilityCalculatorTest.zeroUseUsesTheFloorAndHasNoMtbf` (Task 1).
2. **The only unit of its model, or twins that never failed** → `vsModel` empty, no `Infinity`/`NaN` in the JSON, level decided by the count rule alone. Tests: `noTwinsMeansNoRatio`, `twinsWithoutFailuresMeanNoRatio` (Task 1); `AdminReliabilityControllerTest.nullNumbersAreJsonNull` (Task 2).
3. **Only rejected reports on a unit** → it had no failure, so it is not listed at all. Test: `onlyRejectedReportsMeansNotListed` (Task 1).
4. **An incident on an asset the catalog no longer returns** → that unit is skipped, the rest of the report is still built (no 404 for the whole page). Test: `ReliabilityServiceTest.unknownAssetIsSkipped` (Task 2).
5. **A retired unit** → still listed with its status (the admin sees that the lemon is already out), and it still counts in its model's comparison. Test: `ReliabilityCalculatorTest.retiredUnitIsListedAndCountsForItsModel` (Task 1).

---

### Task 1: `ReliabilityCalculator` (PR 1; needs nothing from teammates)

**Files:**
- Create: `backend/src/main/java/com/campus/incident/service/{ReliabilityCalculator,UnitInput,IncidentInput}.java`
- Create: `backend/src/main/java/com/campus/incident/web/{AssetReliabilityItem,ReliabilityLevel}.java`
- Test: `backend/src/test/java/com/campus/incident/service/ReliabilityCalculatorTest.java`
- Create: `documentation/Visan_Stefania/NN-reliability-calculator.md` + 02 + README rows

**Interfaces:**
- Produces:
  - `record UnitInput(Long assetId, String name, String model, String status, long usedMinutes)`
  - `record IncidentInput(Long assetId, boolean rejected, boolean severe, LocalDateTime createdAt, LocalDateTime resolvedAt)` (`resolvedAt` set only for a resolved incident, else `null`)
  - `enum ReliabilityLevel { INSPECT, WATCH }`
  - `record AssetReliabilityItem(Long assetId, String assetName, String model, String status, int failures, int severeFailures, BigDecimal usageHours, BigDecimal failuresPer100h, BigDecimal mtbfHours, BigDecimal avgRepairHours, BigDecimal vsModel, ReliabilityLevel level, String reason)`
  - `final class ReliabilityCalculator { static List<AssetReliabilityItem> rank(List<UnitInput> units, List<IncidentInput> incidents, int windowDays); }`

- [ ] **Step 1: Write the failing tests** (plain JUnit; helpers `unit(id, model, minutes)`, `failure(assetId)`, `severe(assetId)`, `rejected(assetId)`, `resolved(assetId, created, hoursToFix)`; window 180 unless stated):
  - `workedExampleFromTheSpec`: units #1 (60 h), #2 (40 h), #3 (60 h) of `DS1054Z`; #2 has 2 failures + 1 rejected; #1 has 1 failure → first row is #2 with `failures 2`, `usageHours 40.0`, `failuresPer100h 5.0`, `vsModel 6.0`, `INSPECT`, reason `"2 incidents in the last 180 days; fails 6.0× more often than other DS1054Z units"`; second row #1 `WATCH`; #3 not listed.
  - `onlyRejectedReportsMeansNotListed`.
  - `threeFailuresIsInspectWithoutAnyRatio` (single unit of its model).
  - `twoFailuresWithoutRatioIsWatch`.
  - `twoFailuresAtExactlyDoubleTheModelRateIsInspect` (unit 2 failures/100 h = 2.0; twins 1 failure/100 h = 1.0 → ratio 2.0).
  - `twoFailuresJustBelowDoubleIsWatch` (twins 1 failure/99 h → ratio 1.98; the sentence has no "fails …" part).
  - `oneFailureWithAHugeRatioIsStillWatch` (the ratio rule needs 2 failures).
  - `lowUseIsCalculatedOnTenHours`: 1 failure, 60 minutes → `failuresPer100h 10.0` (not 100.0), `usageHours 1.0`.
  - `zeroUseUsesTheFloorAndHasNoMtbf`: 1 failure, 0 minutes → `failuresPer100h 10.0`, `mtbfHours null`.
  - `mtbfIsHoursPerFailure`: 2 failures, 50 h → `25.0`.
  - `avgRepairUsesOnlyResolvedIncidents`: resolved after 4 h and after 8 h, plus one open → `6.0`; none resolved → `null`.
  - `severeFailuresAreCountedSeparately`: 1 severe + 1 normal → `failures 2`, `severeFailures 1`.
  - `noTwinsMeansNoRatio`; `twinsWithoutFailuresMeanNoRatio`; `twinsWithLessThanTenHoursTogetherAreIgnored` (→ `vsModel null`).
  - `comparisonIsPerModel`: a failing `Fluke 115` does not change a `DS1054Z` unit's ratio.
  - `retiredUnitIsListedAndCountsForItsModel` (status `RETIRED` kept in the row; its hours and failures are in its twins' baseline).
  - `sortsByLevelThenRateThenId`: one `WATCH` with rate 20.0 (2 failures, 5 h, no twins), two `INSPECT` with rate 5.0 (3 failures, 60 h each; ids 9 and 4, different models) → order 4, 9, then the `WATCH` (level wins over a higher rate).
  - `singularSentenceForOneIncident`: `"1 incident in the last 90 days"` with window 90.
  - `publishedNumbersHaveOneDecimal`: 1 failure, 70 h → `failuresPer100h 1.4` (1.428… rounded), scale 1.
- [ ] **Step 2: Run** `./gradlew test --tests ReliabilityCalculatorTest` → FAIL.
- [ ] **Step 3: Implement.** Work in `double` on minutes and counts, group units by model once, round only when building the row (`BigDecimal.valueOf(x).setScale(1, RoundingMode.HALF_UP)`). Constants at the top with their *why* (spec §2 rows 2–5). Comment at the ratio: the unit itself is left out of its model's baseline, otherwise a lemon would hide inside its own average.
- [ ] **Step 4: Run** → PASS; `./gradlew test --tests 'com.campus.architecture.*'` → PASS (if a rule fails on the new package, stop and tell Calin).
- [ ] **Step 5:** `./gradlew spotlessApply build`. Personal doc (Part 1: "incidents per hours of use, compared with twins", with the worked example as a story; Part 2: the formulas table, the constants and why, the test list). Commit `feat(incident): add reliability calculator`, push `feat/incident-reliability-calculator`, open the PR (body: the ask to Calin, linked to the tracking issue).

### Task 2: `ReliabilityService` + endpoint (PR 2; needs both facade methods on `main`)

**Files:**
- Create: `backend/src/main/java/com/campus/incident/service/ReliabilityService.java`
- Create: `backend/src/main/java/com/campus/incident/web/{AdminReliabilityController,ReliabilityReportResponse}.java`
- Test: `backend/src/test/java/com/campus/incident/service/ReliabilityServiceTest.java`, `backend/src/test/java/com/campus/incident/web/AdminReliabilityControllerTest.java`
- Modify: `backend/src/main/java/com/campus/incident/repository/IncidentReportRepository.java` (one query: incidents created at or after a time), `docs/api-contract.md` ("Equipment reliability (Stefania)"), `docs/modules/incident.md`
- Test: `backend/src/test/java/com/campus/incident/repository/IncidentReportRepositoryTest.java` (`@DataJpaTest`; new or existing class)
- Create: `documentation/Visan_Stefania/NN-reliability-report.md` + 02 + README rows

**Interfaces:**
- Consumes: `ReliabilityCalculator.rank`, `UnitInput`, `IncidentInput` (Task 1); the new repository query; Calin's used-minutes read (as merged); `CatalogApi.getAsset(Long)` → `AssetSummary(id, name, model, categoryId, labRoomId, status)` (throws `NotFoundException`); `Clock`.
- Produces: `record ReliabilityReportResponse(int windowDays, LocalDateTime from, LocalDateTime to, List<AssetReliabilityItem> items)`; `@Service class ReliabilityService { ReliabilityReportResponse report(int days); }`; `GET /api/v1/admin/reliability?days=` with `@RequestParam(defaultValue = "180") @Min(30) @Max(365) int days`.

- [ ] **Step 0: Repository query** (`@DataJpaTest`): an incident created exactly at `since` is included, one a second earlier is not. Run → FAIL, add the derived query, run → PASS.
- [ ] **Step 1: Write the failing service tests** (Mockito; `Clock.fixed(2026-11-10T10:00 campus time)`; `NOW = LocalDateTime.of(2026, 11, 10, 10, 0)`):
  - `windowEndsNowAndStartsDaysBefore`: `report(180)` asks both facades for `NOW.minusDays(180)` (… to `NOW`), and the response carries `from`, `to`, `windowDays`.
  - `mapsIncidentsAndUsageIntoTheCalculator`: one unit with 2 failures and 600 minutes → one item with `failures 2`, `usageHours 10.0`.
  - `rejectedStatusIsMappedToRejected` and `highAndCriticalAreMappedToSevere` (pins the mapping from the entity's `IncidentStatus` / `IncidentSeverity` to `IncidentInput`).
  - `unitWithUseButNoIncidentStillCountsForItsModel`: the twin appears in the catalog lookups and in the first unit's `vsModel`, but not in `items`.
  - `unknownAssetIsSkipped`: `getAsset(99)` throws `NotFoundException` → the report is built from the other units.
  - `asksTheCatalogOncePerUnit`: an asset with 3 incidents and usage → `verify(catalogApi, times(1)).getAsset(id)`.
  - `noDataGivesAnEmptyReport`.
- [ ] **Step 2: Run** → FAIL. **Implement** (`@Transactional(readOnly = true)`; truncate "now" to seconds like the rest of the API; comment at the per-unit lookup: the accepted limit from spec §8 and the bulk method that would remove it). Run → PASS.
- [ ] **Step 3: Write the failing controller tests:** `requiresLogin` (401); `studentGets403`; `defaultWindowIs180` (`verify(service).report(180)`); `rejectsDaysBelow30` and `rejectsDaysAbove365` (400 `VALIDATION_FAILED`, body in the message); `returnsTheReport` (`$.items[0].level == "INSPECT"`); `nullNumbersAreJsonNull` (`$.items[0].vsModel` is `null`, present as a key).
- [ ] **Step 4: Run** → FAIL. **Implement** the controller (`@Validated`, `@Tag(name = "Equipment reliability")`, `@Operation` describing the three levels). Run → PASS.
- [ ] **Step 5:** `./gradlew spotlessApply build` → `BUILD SUCCESSFUL`.
- [ ] **Step 6: Real app check** on a fresh database: as two students report three incidents on one seeded unit (reject one as admin), then `GET /api/v1/admin/reliability` → that unit is listed with `failures 2`; `?days=29` → 400; as a student → 403. Paste the JSON in the personal doc.
- [ ] **Step 7:** api-contract section, `docs/modules/incident.md`, personal doc. Commit `feat(incident): add admin reliability report`, push `feat/incident-reliability-report`, open the PR.

### Task 3: Equipment health page (PR 3)

**Files:**
- Create: `frontend/src/api/reliability.ts`, `frontend/src/pages/AdminReliabilityPage.tsx`
- Modify (her own files): `frontend/src/App.tsx` (route `reliability` under `admin`), `frontend/src/layout/navigation.ts` (entry `{ label: 'Equipment health', path: '/admin/reliability', icon: IconHeartbeat }` in the admin "Equipment" group)
- Create: `documentation/Visan_Stefania/NN-reliability-page.md`, screenshots `docs/screenshots/reliability-0*.png`

**Interfaces:**
- Consumes: `GET /api/v1/admin/reliability?days=` (Task 2); `apiFetch`; `ErrorAlert`, `PageHeader` (existing shared components, used as they are).
- Produces: `type ReliabilityLevel = 'INSPECT' | 'WATCH'`; `type AssetReliabilityItem = { assetId: number; assetName: string; model: string; status: string; failures: number; severeFailures: number; usageHours: number; failuresPer100h: number; mtbfHours: number | null; avgRepairHours: number | null; vsModel: number | null; level: ReliabilityLevel; reason: string }`; `type ReliabilityReport = { windowDays: number; from: string; to: string; items: AssetReliabilityItem[] }`; `fetchReliability(days: number): Promise<ReliabilityReport>`.

- [ ] **Step 1:** `reliability.ts` with the types and the call (JSDoc names the endpoint and that `null` means "not enough data").
- [ ] **Step 2:** The page exactly as spec §7: `SegmentedControl` 90 / 180 / 365 (default 180), table columns in the spec's order, `null` shown as "–", `vsModel` as `6.0×`, level `Badge` red/yellow, unit name as a `Link` to `/catalog/:id`, the "How is this calculated?" text taken from spec §5.3 + §2 row 3, the empty-state sentence from the spec. Load in an effect keyed on `days`; error reset and spinner stop in `finally`.
- [ ] **Step 3:** Route + menu entry.
- [ ] **Step 4: Manual check:** the unit from Task 2's check is first with a red badge and the sentence; switching the window reloads; a student opening `/admin/reliability` is redirected like on the other admin pages; phone width keeps the table usable (horizontal scroll, like the desk page).
- [ ] **Step 5:** `npm run lint && npm run build`; screenshots (light + dark); personal doc; commit `feat(frontend): add equipment health page`; push `feat/frontend-reliability`; PR.

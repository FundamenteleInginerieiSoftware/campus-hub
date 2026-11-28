# 61. Maintenance Simulator Page Review Nits (#352 Follow-up)

**Author:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Follow-up to:** Merged PR #352 (`feat(reassignment): add Maintenance simulator page in admin dashboard (#58 PR 2)`)  
**Module:** `frontend/` (`MaintenanceSimulatorPage.tsx`)

---

## Part 1: In simple terms

This follow-up PR cleans up five small UI and documentation details noted during the review of PR #352 on the admin **Maintenance simulator** page:

1. Styled the substitute equipment links in the results table with Mantine's theme link styling instead of raw browser-default links.
2. Changed the **Disruption score** card icon from a checkmark to a warning triangle whenever the disruption score is greater than zero.
3. Cleared previous simulation results and API errors as soon as the admin edits the asset or time window inputs, and displayed the exact simulated time window above the KPI summary cards.
4. Computed the default weekday window only once on initial mount rather than on every re-render.
5. Corrected the wording in doc `58-maintenance-simulator-admin-page.md` from `"skipping Fri/Sat"` to `"skipping Sat/Sun"`.

---

## Part 2: Technical details

| Item | File | Fix |
|---|---|---|
| Mantine link styling | `frontend/src/pages/MaintenanceSimulatorPage.tsx` | Replaced `<Link to={...}>` with `<Anchor component={Link} to={...} size="sm">` (imported `Anchor` from `@mantine/core`). |
| Disruption KPI icon | `frontend/src/pages/MaintenanceSimulatorPage.tsx` | Rendered `<IconAlertTriangle size={20} />` when `result.disruptionScore > 0` and `<IconCheck size={20} />` only when `result.disruptionScore === 0`. |
| Stale result/error reset + window subtitle | `frontend/src/pages/MaintenanceSimulatorPage.tsx` | Cleared `result`, `error`, and `validationError` in `selectedAssetId`, `windowStart`, and `windowEnd` `onChange` handlers; rendered `formatCampusDateTime(result.windowStart) → formatCampusDateTime(result.windowEnd)` above the KPI cards. |
| Lazy default window init | `frontend/src/pages/MaintenanceSimulatorPage.tsx` | Replaced per-render `const defaults = defaultWindowInputs()` with `const [initialDefaults] = useState(defaultWindowInputs)` so `Intl.DateTimeFormat` is not constructed on every render. |
| Doc wording fix | `documentation/Murariu_Calin/58-maintenance-simulator-admin-page.md` | Changed `"skipping Fri/Sat"` to `"skipping Sat/Sun"`. |

### Verification
- `cd frontend && npm run lint && npm run build` — **0 warnings, 0 errors**.

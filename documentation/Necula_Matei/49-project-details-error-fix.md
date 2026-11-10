# Project details: old error and stale clock (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (owner of the `project` module)
**Date:** 2026-11-10
**Branch / PR:** `fix/frontend-project-details-error` (PR #?)
**See also:** `41-projects-pages.md`, `42-project-reserve-all.md`

---

## Part 1: In simple terms

1. **Old error.** If loading a project failed once (backend hiccup) and the next try worked, the red error box stayed above the working page. Now a successful load removes it.
2. **Old clock.** The page read the time once when it opened. A tab left open overnight still offered yesterday's slots. Now the page reads the campus clock again whenever it redraws, and again right before "Check availability" and "Reserve all". If the chosen slot has already started, the selects jump to the first bookable slot and the student checks again.

**Result:** no stale error after a good reload; no booking attempt for a slot that is already in the past.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `frontend/src/pages/ProjectDetailsPage.tsx` | `setError(null)` in the load effect's success path; `now` read on each render; `dropSlotIfPast` guard; `changeDay` reads the clock itself |
| `documentation/Necula_Matei/49-project-details-error-fix.md`, `README.md` | This doc + index row |

| Bug | Cause (on `origin/main`) | Fix |
|---|---|---|
| Error stays | The effect's `.then` (line ~189) never called `setError(null)`; only `changeLang` and `checkAvailability` cleared it. The 409 reload after a failed Reserve all, and any other reload, did not | `setError(null)` when a load succeeds |
| Stale `now` | `const [now] = useState(nowInBucharest)` (line 160) froze the clock at mount; `days` was memoised on it | `const now = nowInBucharest()` per render; `days = bookableDays(now)` (about 15 x 24 loop steps, cheap); `dropSlotIfPast` re-reads the clock on click |

- The Reserve all error (`reserveError`) is still kept on a 409 so the student reads it while the table reloads; `checkAvailability` clears it as before.
- Reuses Calin's `nowInBucharest()` from `api/reservations.ts`; no new helper.
- Not fixed, only noted: when the route `slug` changes without leaving the page, the old `project` stays visible until the new one loads (the effect doesn't reset it).

**Tests:** `cd frontend && npm run lint` (0 warnings, 0 errors) and `npm run build` (OK, only the usual chunk-size warning).

**Manual recipe**
1. Error: open `/projects/<slug>`, stop the backend, click Check availability (red error), start the backend, click again. The error box disappears.
2. Clock: in dev tools override the time (or wait until the selected start passes), then click Check availability: the selects move to the first free slot and no request is sent.

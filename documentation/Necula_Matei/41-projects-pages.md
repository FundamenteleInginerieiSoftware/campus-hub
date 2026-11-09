# Projects pages: search list and project details (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (owner of the `project` module)
**Date:** 2026-11-09
**Branch / PR:** `feat/frontend-projects` (issue #140)
**See also:** `38-project-assistant-design.md` (design), `39-project-engine.md` (the backend these pages call), spec §8

---

## Part 1: In simple terms

1. **A new "Projects" entry in the side menu** (students and admins, under "Equipment"). It opens a page with a search box: the student types what they want to build ("termometre", "measure a PWM signal") and the list updates 300 ms after they stop typing (or at once on Enter).
2. **The list** shows each matching project: title, short summary, difficulty, hours and how many items it needs. When two projects match almost equally, the page asks **"Did you mean: … or …?"** with both as buttons. When nothing matches, it says so and offers "Show all projects".
3. **RO / EN switch** at the top right of both pages. It changes the language of the project texts (title, steps, mistakes). The browser remembers the choice for the next visit; the default is Romanian.
4. **The project page** shows the equipment from our lab (what it is for, how many are needed, how many exist), what to bring from home, the numbered steps and the common mistakes.
5. **"Book the kit" card**: the student picks a day, a start and an end time. The lists only offer slots the booking rules allow (Monday–Friday, 08:00–20:00, half-hour marks, at most 4 hours, at most 14 days ahead, lab time zone). "Check availability" then shows, per item, how many units are **free in that slot**, in red when there are fewer than needed, and says whether the whole kit is free.
6. **Not in this PR:** the "Reserve all" button. It comes in the next PR, together with the backend endpoint that books every item at once.

**Result:** a student can find a project, read it in Romanian or English and see whether the whole kit is free at a time they choose.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `frontend/src/api/projects.ts` | New. Types matching `com.campus.project.web` records; `searchProjects(q, lang)`, `fetchProject(slug, lang, slot?)` via the shared `apiFetch`; `loadProjectLang` / `saveProjectLang` (localStorage in try/catch, default `ro`); `projectErrorMessage` (local text for `PROJECT_*` codes) |
| `frontend/src/pages/ProjectsPage.tsx` | New. `/projects`: search box, table, "Did you mean" chips, empty state, RO/EN switch |
| `frontend/src/pages/ProjectDetailsPage.tsx` | New. `/projects/:slug`: equipment table, bring-yourself, steps, mistakes, slot card with "Check availability" |
| `frontend/src/pages/ProjectsParts.tsx` | New. `DifficultyBadge`, `LangSwitch`, `ProjectErrorAlert` (shared by both pages) |
| `frontend/src/App.tsx` | 2 routes (`projects`, `projects/:slug`) under the logged-in layout, same pattern as the desk page (#138). Stefania's file: OK asked on the PR |
| `frontend/src/layout/navigation.ts` | "Projects" menu entry (icon `IconBulb`) in both menus. Stefania's file: OK asked on the PR |
| `docs/screenshots/projects-0[1-9]-*.png` | Screenshots (desktop + phone width) |

### Key decisions and why

| Decision | Why |
|---|---|
| Search on the backend only, no client filtering | `ProjectSearch.java` handles diacritics, stop words and plural/singular in both languages; the page just sends `q` |
| `useDebouncedCallback` (300 ms) + Enter calls `cancel()` then searches at once | Enter must not wait, and the pending timer must not fire a second identical search |
| `changeQuery` / `changeLang` return early when the value didn't change (trimmed) | The #133/#196 bug class: setting `loading = true` without a state change means the effect never runs and the spinner never stops. Every effect ends `loading` in `finally`, only for the latest request (`ignore` flag) |
| Slot selects only offer valid slots (local helpers `startTimesFor`, `endTimesFor`, `bookableDays`) | `GET /projects/{slug}` doesn't check booking rules (spec §7); offering only bookable slots means the availability shown is for a slot that Reserve all (PR 5) can really book. Calin's helpers in `CalendarBookingView.tsx` aren't exported and his files aren't ours to edit, so we reuse only his exported `nowInBucharest()` from `src/api/reservations.ts` |
| `slotToCheck` (what to send) vs. `checkedSlot` (what the numbers on screen are for) | If the student changes the selects after checking, the page says "check availability again" and `kitReservable` turns false, so PR 5's button can't book a slot that was never checked |
| `checkAvailability` sets a new object each click | A second click on the same slot reloads fresh numbers (someone may have booked in between) |
| Local `PROJECT_MESSAGES` + fallback to `ErrorAlert` | `errorMessages.ts` is Stefania's shared file; we don't add our codes there |
| Badge label `overflow: visible` | Mantine's badge hides overflow, so the table column shrank "Intermediate" to "INTERMEDI…" |
| Phone layout: equipment table, then the slot card, then steps | The student sees the numbers change right above the button; hours/items columns hidden below `sm` |

### For PR 5 (Reserve all)

In `ProjectDetailsPage.tsx`: `selectedSlot` (the slot in the selects, `Slot | null`), `checkedSlot` (slot of the numbers on screen), `kitReservable` (`selectedSlot` == `checkedSlot` and `project.reservable`). The button goes under "Check availability", where the comment `Reserve all (PR 5) goes here` is. `Slot` is exported from `projects.ts`.

### How it was tested

| Check | Result |
|---|---|
| `npm run lint` | 0 warnings, 0 errors |
| `npm run build` | built (only the existing > 500 kB chunk warning) |
| Real run: backend on :8094 (dev profile, scratch PostgreSQL 18), Vite on :5184, logged in as `student1` | Headless Chromium script: search "termometre" → thermometer; "multimetru osciloscop" → "Did you mean" with 2 chips; "xyz" → empty state + "Show all projects" → 3 rows; EN switch → "Digital thermometer"; Enter on the same query, a trailing space, a second check on the same slot → no spinner left (0 loaders each time); changing "To" after a check → "check availability again"; unknown slug → "This project does not exist"; phone width 390 px → no horizontal scroll |
| Availability | The dev seed has no Arduino/DS18B20/LCD/breadboard yet (Liviu seeds them, #140). For the screenshot I added 3 assets through the admin API in my scratch database only: the thermometer shows breadboard 0 in red, the oscilloscope project shows "Every item is free" |

### Problems hit and fixes

| Problem | Fix |
|---|---|
| Mantine 9 `Grid` has no `gutter` prop (TypeScript error) | `gap="lg"`, like Calin's `CalendarBookingView` |
| Difficulty badge cut to "BEGI…" on a phone | Badge label `overflow: visible` (see above) |
| On a phone the slot card ended up below all the steps | Equipment + slot card in the grid, steps and mistakes below it |

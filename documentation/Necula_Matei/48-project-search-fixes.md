# Projects: search false hits and a tidier ProjectService (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (owner of the `project` module)
**Date:** 2026-11-10
**Branch / PR:** `fix/project-search-prefix` (issue #140, found by the 10 Nov code audit)
**See also:** `39-project-engine.md` (the search engine), `40-project-content.md` (the real keywords)

---

## Part 1: In simple terms

1. **The problem.** Searching "multiplexer" found the multimeter project. The search treated two words as "the same word" as soon as their first 5 letters matched, and both start with "multi".
2. **Why we can't just compare whole words.** Students type "termometre" (plural) or "termometrul" (with the article), while the project keyword is "termometru". These must still match, and neither word simply starts with the other.
3. **The new rule.** Two words count as the same word when they share at least their first 5 letters **and** they differ only in the last 1 or 2 letters of the shorter word. Romanian and English endings (plural, article) live exactly there; different words split much earlier.
4. **Two small clean-ups in `ProjectService`.** The search no longer opens a database transaction (it never talks to the database), and the project details method is split into small steps with clear names.

**Result:** "multiplexer" and "termostat" no longer find anything; "termometre", "termometrul", "senzori", "multimetre", "batteries" still find their keyword. All 18 typical queries in `ShippedProjectsTest` still rank the right project first.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `backend/src/main/java/com/campus/project/service/ProjectSearch.java` | New `sameStem(a, b)` replaces the "first 5 letters equal" check; new constant `MAX_ENDING = 2`; comments on `PREFIX`/`MIN_PREFIX` updated |
| `backend/src/main/java/com/campus/project/service/ProjectService.java` | `search` without `@Transactional`; `details` split into `requireWholeSlotOrNone`, `toComponentView`, `countFreeUnits`, `toDetailsResponse` |
| `backend/src/test/java/com/campus/project/service/ProjectSearchTest.java` | +2 parameterized tests: `inflectedFormFindsKeyword` (6 pairs), `sharedStartIsNotEnough` (3 pairs) |
| `backend/src/test/java/com/campus/project/service/ShippedProjectsTest.java` | +`unrelatedWordWithSameStartFindsNothing` ("multiplexer" on the real project files) |
| `docs/superpowers/specs/2026-11-09-project-assistant-design.md`, docs 38 + 39 | Rule description updated |

### The matching rule

`shared` = number of equal first letters, `shorter` = length of the shorter word.
Same stem ⇔ `shared ≥ 5` **and** `shared ≥ shorter − 2`.

| Query | Keyword | Shared start | Needed | Before | Now |
|---|---|---|---|---|---|
| termometre | termometru | 9 (`termometr`) | 8 | ✅ | ✅ |
| termometrul | termometru | 10 | 8 | ✅ | ✅ |
| senzori | senzor | 6 | 5 | ✅ | ✅ |
| multimetre | multimetru | 9 | 8 | ✅ | ✅ |
| batteries | battery | 6 | 5 | ✅ | ✅ |
| gradele | grade | 5 | 5 | ✅ | ✅ |
| **multiplexer** | **multimeter** | 5 (`multi`) | 8 | ✅ (bug) | ❌ |
| **termostat** | **termometru** | 5 (`termo`) | 7 | ✅ (bug) | ❌ |
| pwms | pwm | 3 | 5 | ❌ | ❌ |
| motoare | motor | 4 (`moto`) | 5 | ❌ | ❌ (both are listed as keywords) |

**Checked against the real files.** A script compared every pair of keyword words in `resources/projects/*.json` under the old and new rule. Only 4 pairs stop matching (`oscilloscope`/`osciloscop`, `printare`/`printing`, `printata`/`printing`, `ultrasonic`/`ultrasunete`), and in each pair both words are keywords of the **same** project, so no query loses its project.

### Why each change

| Change | Why |
|---|---|
| `sameStem` instead of "first 5 letters" | Keeps the inflection matches (doc 39) and drops words that only share a beginning. A plain `startsWith` (the audit's idea) would lose `termometre` ↔ `termometru` |
| No `@Transactional` on `search` | It only reads the in-memory `ProjectLibrary`; a read-only transaction would still borrow a database connection for nothing |
| `@Transactional(readOnly = true)` kept on `details` | It calls Liviu's `CatalogApi.findAvailableByModel` and Calin's `ReservationApi.isAssetFree` once per component; one transaction for the whole page |
| "proiect", "proiectul", "proiecte", "project", "projects" as stop words | "proiect" matched the keyword "proiectare" (design) of `printed-enclosure`, so "vreau un proiect cu termometru" also listed that project. Every result is a project, so the word adds nothing. Test: `ShippedProjectsTest.theWordProjectIsFiller` (only `thermometer` is found) |
| `details` split | It was ~35 lines doing four jobs (check the slot, count units, decide "reservable", build the answer). Now `details` reads as those four steps; behavior is the same (the existing `ProjectServiceTest` passes unchanged) |

### How it was tested

| Step | Command | Result |
|---|---|---|
| Red: new tests on the old `ProjectSearch` | `./gradlew --no-daemon test --tests '*ProjectSearchTest' --tests '*ShippedProjectsTest'` | 4 failed: the 3 `sharedStartIsNotEnough` pairs + `unrelatedWordWithSameStartFindsNothing`; the 6 inflection pairs passed (they must keep working) |
| Green: after the fix | same | all passed |
| Red/green of `theWordProjectIsFiller` | old `ProjectSearch` + new test, then the fix | fails without the stop words, passes with them |
| Full build (scratch PostgreSQL 18) | `./gradlew --no-daemon spotlessApply build` | BUILD SUCCESSFUL, 998 tests, 0 failures (999 with the stop-word test) |

**Problem hit.** `motoare` ↔ `motor` was first in my "must still match" list, but the words share only `moto` (4 letters), so it never matched, not even before. It's covered because the JSON lists both forms as keywords, so I took it out of the test.

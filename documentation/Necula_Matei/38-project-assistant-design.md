# Design: the Projects feature ("project assistant") (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (this feature: owner of the new `project` module)
**Date:** 2026-11-09
**Branch / PR:** `docs/project-assistant-spec` (issue #140)
**See also:** `docs/superpowers/specs/2026-11-09-project-assistant-design.md` (the spec), `docs/superpowers/plans/2026-11-09-project-assistant.md` (the plan for the first two PRs), #140 (design questions and the team's answers)

---

## Part 1: In simple terms

1. **What it is.** A student types what they want to build ("vreau să construiesc un termometru"). The app shows the lab equipment needed, how many units are free, the steps, the usual mistakes, and what to bring from home. A **Reserve all** button books the whole kit for one time slot, or nothing if one item is taken.
2. **No AI.** The answers come from about 10 project descriptions we write ourselves, and from our own search code. The same question always gets the same answer, so the demo is safe. Every step can be tested and explained, and there's no cost and no secret key.
3. **Two languages.** Every project is written in Romanian and English. Search understands both. "termometre" still finds "termometru", and missing diacritics don't matter.
4. **Each person builds their own part.** I build the new module and its pages. Calin adds one method to his booking module so a whole kit can be booked at once. Liviu adds the missing equipment (sensors, breadboards, a power supply…) to his test data. Stefania picks how the whole app switches language. I only asked them on #140; I don't write their code.
5. **This PR has only the design.** It holds the full design, the step-by-step plan for the first two code PRs, and one new line in the team rules saying the new module may use the catalog and reservation modules.

**Result:** the team can read and comment on the design before any code. The code comes in later PRs: engine, content, pages, then Reserve all.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `docs/superpowers/specs/2026-11-09-project-assistant-design.md` | The spec: goal, decisions from #140, ownership, architecture, JSON format + startup validation, search algorithm (scores 3/2/1, prefix ≥ 5, ambiguity gap 1), API, frontend, tests, delivery in 5 PRs, out of scope |
| `docs/superpowers/plans/2026-11-09-project-assistant.md` | Plan for PR 1 (this one) and PR 2 (`feat/project-engine`): 6 tasks, exact interfaces, test names, review focus (unknown JSON field, punctuation-only query, upper case/cedilla, maintenance units/bad slot, files inside the jar) |
| `AGENTS.md` §5 | Allowed dependencies: `catalog.api, reservation.api <- project`; `project` is a leaf (nothing depends on it) |

### Decisions (from #140)

| Topic | Choice | Why |
|---|---|---|
| Engine | Our own templates + search, no LLM | Testable, same answer every time, no cost, no secret in SSM |
| Storage | JSON files in `resources/projects/`, read at startup | ~10 projects; a content fix is a file edit, not a new Flyway migration; PostgreSQL's Romanian stemmer doesn't match *termometre* ↔ *termometru* (tested on PG 18) |
| Search | Normalize (lowercase, strip diacritics incl. `ş ţ`), stop words, exact keyword +3, prefix ≥ 5 letters +2, title word +1, rank, "did you mean" when the top two are within 1 point | Every number has a unit test and a reason |
| Reserve all | Backend picks the lowest-id free unit per model, then one call to `ReservationApi.bookBundle` (Calin) | One transaction, all or nothing; deterministic choice like the reassignment engine (#99) |
| Language | `?lang=ro\|en`, search matches both; page-level switch until the whole-app approach is picked | Doesn't wait on #140 §4 |
| Safety at startup | Invalid project file → the app refuses to start, naming the file and field (also unknown fields: Jackson 3 ignores them by default) | A typo can't reach the demo |

### Who does what (asked on #140, no code written for anyone)

| Who | Ask |
|---|---|
| Calin | `ReservationApi.bookBundle(...)`; 1-item kits; #228 point 4 (kit = 1 booking both ways); `project` in the ArchUnit module list |
| Liviu | Seed 8 models: `Arduino Uno R3`, `DS18B20 temperature sensor kit`, `HC-SR04 ultrasonic sensor kit`, `16x2 I2C LCD module kit`, `2WD robot chassis + L298N kit`, `Breadboard + jumper wire kit`, `DP832`, `FX-888D` |
| Stefania | Whole-app RO/EN approach; OK for the `projects` routes + menu entry |

### Delivery

| PR | Content | Waits on |
|---|---|---|
| 1 (this) | Spec, plan, AGENTS.md line, this doc | – |
| 2 | Engine: library, search, details with availability, 2 GET endpoints, 3 projects, tests | – |
| 3 | The other ~7 projects | PR 2 |
| 4 | Projects pages | PR 2, Stefania's OK on routes |
| 5 | `POST /reserve` + Reserve all button | Calin's `bookBundle` |

### Checked

- The spec and plan were reviewed against `main` (`fb9c966`): `CatalogApi.findAvailableByModel(String, Long)` and `ReservationApi.isAssetFree(...)` exist with these signatures. The bundle endpoint allows 2–5 assets (`CreateMultiAssetReservationRequest`). The booking rules (30 min–4 h, 30-min steps, weekdays 08:00–20:00, ≤ 14 days) live in `ReservationService`.
- The ArchUnit `FEATURE_MODULES` list doesn't include `project` yet. The rules don't fail on it, but they don't check it either, so Calin has been asked to add it.

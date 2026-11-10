# Project assistant ("Projects" pages): design

**Status:** approved by Matei, 2026-11-09. **Issue:** #140. **Owner:** Matei (new module `com.campus.project`).
**Deadline:** before the feature freeze (Mon 7 Dec, W10); must not slow down M1 (15 Nov).

## 1. Goal

A student types a project ("vreau să construiesc un termometru", "line follower robot") and gets:

- the **equipment** it needs from our catalog, with how many units are free;
- **steps** with short explanations, **common mistakes**, and a **"bring it yourself"** list;
- a **Reserve all** button: pick a date and time, every item is booked in one go (all or nothing).

Success = in the demo, a student searches "termometru", opens the project, sees which items are free and books the kit with one click; every decision is unit-tested and explainable at the defense.

## 2. Decisions (from #140)

| # | Decision |
|---|---|
| 1 | Only equipment from our catalog can be suggested; anything else goes in "bring it yourself" |
| 2 | Our own template engine, **no AI/LLM** (same answer every time, testable, no cost, no secret) |
| 3 | Two pages (Projects list + search, Project details). No chat |
| 4 | Content in RO + EN. The API takes `?lang=ro\|en`; search matches both languages. The pages have their own RO/EN switch until Stefania picks the whole-app approach (#140 §4) |
| 5 | A kit is booked through Calin's bundle booking (#212): all or nothing |
| 6 | Matei drafts ~10 projects; the team checks the content on the PR |
| 7 | Projects are JSON files in `resources/projects/`, loaded at startup; search is our own Java code. No tables, no migration |
| 8 | The **backend** picks one free unit per model and books them through a `ReservationApi` facade method that Calin writes |

## 3. Ownership: what we build vs. what we ask for

We never write teammates' code (AGENTS.md §3). We only ask on #140.

| Who | What | Status |
|---|---|---|
| Matei | `com.campus.project` (domain, service, web), `resources/projects/*.json`, `frontend/src/api/projects.ts`, `ProjectsPage.tsx`, `ProjectDetailsPage.tsx`, api-contract section, AGENTS.md §5 dependency line, personal docs | This spec |
| Calin | `ReservationApi.bookBundle(...)` (in-process, same rules as `POST /reservations/bundle`); allow 1-item kits or say which method to use; #228 point 4 (kit counts as 1 both ways); add `project` to the ArchUnit module list | Asked on #140 |
| Liviu | Seed the models in §5.3 in `CatalogSeeder` | Asked on #140 |
| Stefania | Whole-app RO/EN; OK for the `projects` routes + menu entry in `App.tsx`/layout | Asked on #140 |

## 4. Architecture

```
com.campus.project
  domain/   Project, ProjectComponent, ProjectStep, LocalizedText, Difficulty  (records/enums, NOT entities)
  service/  ProjectLibrary            loads + validates resources/projects/*.json at startup
            ProjectSearch             normalize, score, rank (pure Java, no Spring, no DB)
            ProjectService            list/search/details + availability, @Transactional(readOnly = true)
            ProjectReservationService Reserve all: picks units, calls ReservationApi.bookBundle
  web/      ProjectController + request/response records
```

- **Dependencies:** `project → catalog.api, reservation.api, common`. Nobody depends on `project`. No `api/` package (no consumer). AGENTS.md §5 gets the line `catalog.api, reservation.api <- project`.
- **No new tables.** The JSON is read once at startup (`@PostConstruct` in `ProjectLibrary`), kept in an immutable `List<Project>`.
- The ArchUnit module rules (Calin's) may need `project` in their module list: if a rule fails on the new package, ask Calin; never edit his test.
- Times: `start`/`end` are `LocalDateTime` (campus time, ISO without offset), like every booking. "Now" only through the shared `Clock`.

## 5. Data

### 5.1 Project file (`backend/src/main/resources/projects/<slug>.json`)

```json
{
  "slug": "thermometer",
  "difficulty": "BEGINNER",
  "estimatedHours": 3,
  "title":    { "ro": "Termometru digital", "en": "Digital thermometer" },
  "summary":  { "ro": "...", "en": "..." },
  "keywords": { "ro": ["termometru", "temperatura", "senzor"], "en": ["thermometer", "temperature", "sensor"] },
  "components": [
    { "model": "Arduino Uno R3", "quantity": 1, "why": { "ro": "...", "en": "..." } }
  ],
  "bringYourself": [ { "ro": "Cablu USB-B", "en": "USB-B cable" } ],
  "steps":    [ { "title": { "ro": "...", "en": "..." }, "text": { "ro": "...", "en": "..." } } ],
  "mistakes": [ { "ro": "...", "en": "..." } ]
}
```

`difficulty`: `BEGINNER | INTERMEDIATE | ADVANCED`.

### 5.2 Startup validation (`ProjectLibrary`)

The app **refuses to start** with a message naming the file and the field when:

- a file is not valid JSON or misses a required field;
- a text has an empty `ro` or `en`;
- two projects share a `slug`, or the slug is not `[a-z0-9-]+`;
- `quantity < 1`, `estimatedHours < 1`, no components, no steps, or no keywords in one language;
- the same `model` appears twice in one project (use `quantity` instead);
- the **total lendable units** (sum of `quantity`) is above 5, the bundle maximum.

A `model` that is not in the catalog is **not** a startup error (the seeder may not have it yet): it shows as "0 available" and blocks Reserve all.

Jackson 3 (`tools.jackson.databind.json.JsonMapper`, Boot 4) reads the files; `PathMatchingResourcePatternResolver` finds `classpath*:projects/*.json` and reads each file through `getInputStream()` (works inside the Boot jar; `getFile()` would not).

### 5.3 Models we ask Liviu to seed

`Arduino Uno R3`, `DS18B20 temperature sensor kit`, `HC-SR04 ultrasonic sensor kit`, `16x2 I2C LCD module kit`, `2WD robot chassis + L298N kit`, `Breadboard + jumper wire kit`, `DP832`, `FX-888D`. Existing seeded models can be used too: `DS1054Z`, `DSOX1204G`, `Fluke 115`, `DM3058E`, `STM32F407G-DISC1`, `ESP32-S3-DevKitC-1`, `Prusa i3 MK3S+`.

### 5.4 Content

~10 projects, RO + EN, using only the models above: thermometer, line follower robot, parking sensor, ESP32 weather station, digital clock with LCD, measuring a PWM signal with the oscilloscope, testing a circuit with the multimeter, 3D-printed enclosure, STM32 blinky + debugger, bench-powered motor test. The first PR ships 3 of them; the rest come in a content PR the team reviews.

## 6. Search (`ProjectSearch`)

Pure function: `search(String query, List<Project> projects) → SearchResult(List<ScoredProject> hits, boolean ambiguous)`.

1. **Normalize** (query and keywords/titles): lowercase (`Locale.ROOT`), strip diacritics (`Normalizer.Form.NFD` + remove combining marks; covers `ă â î ș ț` and the cedilla forms `ş ţ`), split on anything that is not a letter or digit, drop tokens shorter than 2 characters and stop words (RO: `vreau sa un o pentru de la cu si care imi este din`; EN: `i want to build make a an the for with my of and`).
2. **Score** each project, using both languages:
   - keyword part (the best one per token): equals a keyword **+3**, else has the same stem as a keyword **+2**, else 0. Same stem = the two words share at least their first **5** letters, and the shared start covers the shorter word except at most its last **2** letters (*termometre* ↔ *termometru*, *senzori* ↔ *senzor*; not *multiplexer* ↔ *multimeter*, which share only *multi*);
   - title part: **+1** if the token equals a title word (either language), added on top of the keyword part.
   Duplicate query tokens are removed first, so repeating a word doesn't inflate the score.
3. **Rank**: score descending, then English title ascending (stable demo order). Score 0 is hidden.
4. **Empty or only-stop-word query** → every project, sorted by English title, `ambiguous = false`.
5. **Ambiguous**: at least 2 hits and `top − second ≤ 1` → `ambiguous = true` (the page shows "Did you mean …" with both).

Constants (`EXACT = 3`, `PREFIX = 2`, `TITLE = 1`, `MIN_PREFIX = 5`, `MAX_ENDING = 2`, `AMBIGUITY_GAP = 1`) live together at the top of `ProjectSearch`, each with a one-line *why*.

## 7. API (`docs/api-contract.md` gets a "Projects (Matei)" section)

All under `/api/v1/projects`, logged-in users (the existing `/api/v1/**` rule; no `SecurityConfig` change). `lang` is `ro` or `en`, default `en`; anything else → `400 VALIDATION_FAILED`.

### `GET /api/v1/projects?q=&lang=`
`200` → `ProjectSearchResponse { ambiguous: boolean, items: ProjectListItem[] }`, where `ProjectListItem { slug, title, summary, difficulty, estimatedHours, componentCount, score }`. Not paged: the library has ~10 items (documented). No availability here (keeps the list to zero catalog calls).

### `GET /api/v1/projects/{slug}?lang=&start=&end=`
`200` → `ProjectDetailsResponse { slug, title, summary, difficulty, estimatedHours, components: ComponentView[], bringYourself: string[], steps: {title, text}[], mistakes: string[], slotChecked: boolean, reservable: boolean }`, where `ComponentView { model, quantity, why, available }`.

- Without `start`/`end`: `available` = units of that model with status `AVAILABLE` (`CatalogApi.findAvailableByModel(model, null).size()`); `slotChecked = false`.
- With both: `available` = those units that are also free in the slot (`ReservationApi.isAssetFree(id, start, end, null)`); `slotChecked = true`. Only one of the two given, or `end` not after `start` → `400 VALIDATION_FAILED`. Booking rules (opening hours, 30-min steps, 4 h, 14 days) are **not** checked here; Reserve all checks them through Calin's facade.
- `reservable` = every component has `available ≥ quantity`.
- Unknown slug → `404 PROJECT_NOT_FOUND`.

### `POST /api/v1/projects/{slug}/reserve` (built after Calin's `bookBundle` is on `main`)
Body `ReserveProjectRequest { start, end }` (`@NotNull` both). `201` → `ReserveProjectResponse { bundleId, start, end, items: { assetId, assetName, model }[] }`.

Steps (`ProjectReservationService`, `@Transactional`):
1. Load the project (`404 PROJECT_NOT_FOUND`).
2. For each component, in file order: candidates = `findAvailableByModel(model, null)` sorted by id ascending (deterministic, like the reassignment engine's tie-break, #99); keep those with `isAssetFree(...)`; take the first `quantity` (a model appears once per project, §5.2, so no unit can be picked twice). Fewer than `quantity` → `409 PROJECT_COMPONENT_UNAVAILABLE` with the model in the message.
3. `reservationApi.bookBundle(currentUser.id(), pickedIds, start, end)`. Its errors pass through unchanged (`RESERVATION_CONFLICT` if someone booked a unit in between, `RESERVATION_LIMIT_REACHED`, `INVALID_DURATION`, `OUTSIDE_OPENING_HOURS`, …). No retry loop: the page says "one item was just taken, check again".
4. The user id comes from `CurrentUser` (never from the body).

Error codes added to the contract: `PROJECT_NOT_FOUND` (404), `PROJECT_COMPONENT_UNAVAILABLE` (409). Thrown with the shared `NotFoundException` / `ConflictException`.

## 8. Frontend (Matei's pages, Stefania's conventions)

- `src/api/projects.ts`: typed calls through the shared `apiFetch` (no change to `client.ts`).
- `ProjectsPage.tsx` (`/projects`): search box ("Describe your project…", 300 ms debounce, also on Enter), table (title, difficulty badge, hours, components), "Did you mean" chips when `ambiguous`, empty state, RO/EN `SegmentedControl` (stored in `localStorage` inside try/catch, default `ro`).
- `ProjectDetailsPage.tsx` (`/projects/:slug`): summary; components table (model, why, needed, available, red when short); bring-yourself list; numbered steps; mistakes; **Reserve all** card with date + start/end selects that follow the booking rules (weekdays, 08:00–20:00, 30-min steps, ≤ 4 h, ≤ 14 days, campus clock from `src/api/reservations.ts` helpers if exported, otherwise a local helper) → "Check availability" (`GET` with `start`/`end`) → "Reserve all" (enabled only when `reservable`) → on 201 go to `/reservations` with a success notification.
- Error texts: a local map for `PROJECT_*` codes on the page, falling back to the shared `errorMessages.ts` for reservation codes (no edit to the shared file).
- Route `projects` + `projects/:slug` and a menu entry: only after Stefania's OK (asked on #140).
- Every load resets its error and stops its spinner in `finally` (the bug class found in #133/#196).

## 9. Testing (AGENTS.md §5: one rule per test, `.as(...)` messages naming the rule and class, fixed `Clock`)

| Test | Kind | Covers |
|---|---|---|
| `ProjectSearchTest` | plain JUnit | exact keyword, prefix plural/singular, shared start alone is no match (*multiplexer* ≠ *multimeter*), diacritics both ways, cedilla `ş`, both languages, stop words only, empty query lists all, no match, token counted once, tie → English title order, ambiguity on/off at the gap |
| `ProjectLibraryTest` | plain JUnit + test fixtures in `src/test/resources/projects-invalid/…` | real files load; each invalid case fails with the file + field in the message; duplicate slug; duplicate model; > 5 units |
| `ProjectServiceTest` | Mockito (`CatalogApi`, `ReservationApi`) | availability without slot, with slot, missing model → 0 + not reservable, half slot → 400, unknown slug → 404, lang switch |
| `ProjectReservationServiceTest` | Mockito | lowest free ids picked, quantity 2, not enough → 409 naming the model, facade errors pass through, user id from `CurrentUser` |
| `ProjectController` slice test (name it like the existing `@WebMvcTest` classes) | `@WebMvcTest` | 401 without token, 400 bad `lang`, 404 code, 200 shapes |
| `findAvailableByModel(model, null)` | check on Liviu's existing `@DataJpaTest` behaviour | if `IdNot(null)` returns nothing, we pass an id that can't exist (`-1L`) instead and document why |

Frontend: `npm run lint && npm run build`, screenshots in the PR.

## 10. Delivery (one PR each, < ~400 lines except content, one personal doc each)

| PR | Branch | Content | Waits on |
|---|---|---|---|
| 1 | `docs/project-assistant-spec` | this spec + AGENTS.md §5 dependency line + doc 38 | – |
| 2 | `feat/project-engine` | domain, `ProjectLibrary`, `ProjectSearch`, `ProjectService`, GET endpoints, 3 projects, tests, api-contract, doc 39 | – |
| 3 | `feat/project-content` | the other ~7 projects (RO + EN) | PR 2 |
| 4 | `feat/frontend-projects` | `projects.ts`, both pages (route + menu once Stefania agrees) | PR 2 |
| 5 | `feat/project-reserve-all` | `POST /reserve`, `ProjectReservationService`, Reserve all button | Calin's `bookBundle` on `main` |

## 11. Out of scope (YAGNI)

Admin editor for projects, AI/LLM text, chat, paging of the project list, saving a student's projects, partial kits (book what is free), automatic retry on a race.

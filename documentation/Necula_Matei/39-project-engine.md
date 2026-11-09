# Project assistant engine: library, bilingual search, and live availability (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (owner of the `project` module)
**Date:** 2026-11-09
**Branch / PR:** `feat/project-engine` (issue #140)
**See also:** `38-project-assistant-design.md` (the design), `docs/superpowers/specs/2026-11-09-project-assistant-design.md` (the spec), `docs/api-contract.md` (Projects section)

---

## Part 1: In simple terms

1. **What a student can do now.** A logged-in student or admin can call two new addresses:
   - `GET /api/v1/projects?q=termometre&lang=ro` to search the project library in Romanian or English;
   - `GET /api/v1/projects/oscilloscope-pwm?lang=en&start=2026-11-16T10:00:00&end=2026-11-16T12:00:00` to open a project's guide (equipment needed, what to bring from home, step-by-step instructions, common mistakes) and see how many units of each model are free in the lab right now or in a chosen time slot.
2. **How search finds "termometre".** When a student types `"vreau să construiesc două termometre"`, the search engine:
   - turns everything to lowercase and strips Romanian diacritics (`ă â î ș ț`, including the older cedilla letters `ş ţ`);
   - removes common filler words (`vreau`, `sa`, `un`, `i`, `want`, `to`, `build`, …);
   - compares each remaining word against both the Romanian and English keywords and titles of every project. Even though the project keyword is singular (`"termometru"`) and the student typed the plural (`"termometre"`), both words are at least 5 letters long and start with the same 5 letters (`"termo"`), so the project gets **+2 points** and comes back first.
3. **Mistakes in project files are caught when the server starts.** The projects live as `.json` files inside the backend. When Spring Boot starts, `ProjectLibrary` checks every file: if a field name has a typo (`"titel"`), a translation is missing, a model is listed twice, or a project needs more than 5 items (Calin's bundle limit), the server refuses to start and prints the exact file and field name.
4. **How availability works without touching other modules' tables.** For each component in a project, `ProjectService` asks Liviu's `CatalogApi` for the operational (`AVAILABLE`) units of that model. If the student also picked a time slot, it asks Calin's `ReservationApi` which of those units have no conflicting booking or hold in that slot.

**Result:** the project library (first 3 projects: digital thermometer, PWM signal with oscilloscope, multimeter basics), bilingual search, and live availability endpoints are tested and running on PostgreSQL 18 and inside the packaged Spring Boot jar.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/src/main/java/com/campus/project/package-info.java` | Module Javadoc: owner (`@matei-necula`), sub-packages (`domain`, `service`, `web`), allowed dependencies (`common`, `catalog.api`, `reservation.api`), leaf module |
| `backend/src/main/java/com/campus/project/domain/{Lang,Difficulty,LocalizedText,Keywords,ProjectComponent,ProjectStep,Project}.java` | Immutable domain records and enums; `Lang.parse` defaults `null`/blank to `EN` and throws `400 VALIDATION_FAILED` (`BadRequestException`) on unknown values |
| `backend/src/main/java/com/campus/project/service/ProjectLibrary.java` | Startup loader (`classpath*:projects/*.json` via `PathMatchingResourcePatternResolver` + `getInputStream()`, Jackson 3 `JsonMapper` with `FAIL_ON_UNKNOWN_PROPERTIES`) and validator |
| `backend/src/main/java/com/campus/project/service/{ProjectSearch,ScoredProject,SearchResult}.java` | Pure Java normalizer, tokenizer, stop-word filter, prefix/exact/title scorer, ranker, and ambiguity detector |
| `backend/src/main/java/com/campus/project/service/ProjectService.java` | `@Transactional(readOnly = true)` search and details with live availability via `CatalogApi.findAvailableByModel` and `ReservationApi.isAssetFree` |
| `backend/src/main/java/com/campus/project/web/{ProjectController,ProjectListItem,ProjectSearchResponse,ComponentView,StepView,ProjectDetailsResponse}.java` | `GET /api/v1/projects` and `GET /api/v1/projects/{slug}` + response records |
| `backend/src/main/resources/projects/{thermometer,oscilloscope-pwm,multimeter-basics}.json` | First 3 bilingual (RO + EN) project definitions |
| `backend/src/test/resources/projects-invalid/<case>/*.json` | 10 invalid JSON fixture folders for startup validation tests |
| `backend/src/test/java/com/campus/project/service/{ProjectLibraryTest,ProjectSearchTest,ProjectServiceTest}.java` | Unit tests for startup validation, bilingual search, and availability |
| `backend/src/test/java/com/campus/project/web/ProjectControllerTest.java` | `@WebMvcTest` + `@Import(SecurityConfig.class)` slice tests |
| `docs/api-contract.md` | §5 `PROJECT_NOT_FOUND` (404), §6 module table row, and `Project endpoints (Matei)` section |

### Search scoring constants (`ProjectSearch`)

| Constant | Value | Why |
|---|---|---|
| `EXACT` | `3` | An exact keyword match is the strongest signal and outranks a prefix match (`2`) plus a title match (`1` alone) |
| `PREFIX` | `2` | Connects Romanian/English inflections (`termometre` ↔ `termometru`, `termometrul` ↔ `termometru`, `osciloscoape` ↔ `osciloscop`) without a full stemmer |
| `TITLE` | `1` | Breaks ties in favor of projects that also name the token in their RO or EN title |
| `MIN_PREFIX` | `5` | Words shorter than 5 characters (`pwm`, `led`, `usb`) must match exactly so short acronyms don't false-match unrelated words |
| `AMBIGUITY_GAP` | `1` | When at least 2 projects match and `top.score - second.score <= 1`, `ambiguous = true` so the UI can show "Did you mean …" chips |

### Startup validation (`ProjectLibrary`)

- Jackson 3 (`tools.jackson.databind.json.JsonMapper`) disables `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` by default; `ProjectLibrary` enables it on the `ObjectReader` so a typo like `"titel"` fails startup naming the file and field instead of loading `null`.
- Files are resolved with `PathMatchingResourcePatternResolver` (`classpath*:projects/*.json`) and read through `Resource.getInputStream()` so loading works both from `build/resources/main` in tests and from nested `BOOT-INF/classes!/projects/*.json` entries inside the packaged Boot jar.
- Every validation error throws `IllegalStateException("projects/<file>: <field> <problem>")`, tested across 10 invalid fixture directories (`unknown-field`, `empty-translation`, `duplicate-slug`, `bad-slug`, `zero-quantity`, `duplicate-model`, `more-than-five-units`, `no-steps`, `no-keywords`, `invalid-json`).

### Availability via `CatalogApi` and `ReservationApi`

- Without `start`/`end`: `ProjectService.details` calls `catalogApi.findAvailableByModel(model, null)` and counts the returned `AVAILABLE` units (`slotChecked = false`, zero calls to `ReservationApi`).
- With `start` and `end`: filters those `AVAILABLE` units with `reservationApi.isAssetFree(asset.id(), start, end, null)` (`slotChecked = true`). Providing only one of `start`/`end` or `end <= start` throws `BadRequestException("VALIDATION_FAILED", ...)`.
- `reservable` is `true` iff every component has `available >= quantity`. Models not yet in the catalog return an empty list (`available = 0`, `reservable = false`).

### Tests and real-app verification

- **Unit & slice tests:** `ProjectLibraryTest` (17 tests), `ProjectSearchTest` (17 tests), `ProjectServiceTest` (11 tests), `ProjectControllerTest` (7 tests), plus `ModuleBoundaryArchitectureTest`, `CodingConventionsArchitectureTest`, and `OpenApiDocsIT` — all green.
- **Packaged fat jar + live PostgreSQL 18 check** (`java -jar build/libs/campus-hub-0.0.1-SNAPSHOT.jar` against `campus_projects_check` on `127.0.0.1:8092`):
  - `GET /api/v1/projects?q=termometre&lang=ro` → `200`, `ambiguous: false`, `items[0].slug = "thermometer"`, `title = "Termometru digital"`, `score = 2`.
  - `GET /api/v1/projects/oscilloscope-pwm` → `200`, `STM32F407G-DISC1` `available = 3`, `DS1054Z` `available = 3`, `slotChecked = false`, `reservable = true` (confirms `findAvailableByModel(model, null)` returns all 3 seeded units on PostgreSQL 18).
  - `GET /api/v1/projects/thermometer` → `200`, `available = 0` on unseeded models, `reservable = false`.
  - `GET /api/v1/projects/nope` → `404` `ProblemDetail` with `"code": "PROJECT_NOT_FOUND"`.

# Project assistant (engine + GET endpoints) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship PR 1 (spec + AGENTS.md dependency line + doc 38) and PR 2 (`feat/project-engine`: project library, search, details with availability, `GET /api/v1/projects` + `GET /api/v1/projects/{slug}`, 3 projects, tests, api-contract, doc 39).

**Architecture:** New module `com.campus.project` (Matei). JSON files in `src/main/resources/projects/` are loaded and validated once at startup by `ProjectLibrary`; `ProjectSearch` is a pure scoring function; `ProjectService` builds responses and asks `CatalogApi` / `ReservationApi` for availability; `ProjectController` exposes two GET endpoints. No tables, no migration, no `SecurityConfig` change.

**Tech Stack:** Spring Boot 4.1.1, Java 25, Jackson 3 (`tools.jackson.*`), JUnit 5 + AssertJ + Mockito, `@WebMvcTest`, Gradle (`./gradlew`), PostgreSQL 18 (local, for `build`).

**Spec:** `docs/superpowers/specs/2026-11-09-project-assistant-design.md`

## Global Constraints

- Base package `com.campus.project`; packages `domain`, `service`, `web` only (no `api`, no `repository`).
- Imports from other modules: only `com.campus.catalog.api.*`, `com.campus.reservation.api.*`, `com.campus.common.*`. Never edit a teammate's file (AGENTS.md §3), not even `errorMessages.ts`, `App.tsx`, ArchUnit tests or `CatalogSeeder`.
- Jackson 3 only: `tools.jackson.databind.*`; annotations stay `com.fasterxml.jackson.annotation.*`.
- Errors: throw `NotFoundException("PROJECT_NOT_FOUND", …)`, `BadRequestException("VALIDATION_FAILED", …)` from `com.campus.common.error`; never build responses in the controller.
- DTOs are `record`s in `web/`; services return them (same pattern as `AssetService` → `AssetResponse`).
- `@Transactional(readOnly = true)` on public `ProjectService` methods only.
- Scoring constants exactly: `EXACT = 3`, `PREFIX = 2`, `TITLE = 1`, `MIN_PREFIX = 5`, `AMBIGUITY_GAP = 1`; max 5 lendable units per project; `lang` ∈ `ro|en` (case-insensitive), default `en`; `q` max 200 chars.
- Tests: one rule per test, every assertion `.as("<rule>; see <Class.method>")`; fixed dates via constants, no `LocalDateTime.now()`.
- Comments only for hidden behaviour, *why*, and links to teammates' code (AGENTS.md §2). Commits as `matei-necula`, Conventional Commits with scope `project`, **no AI trailer**.
- Before each push: `cd backend && ./gradlew spotlessApply build` → `BUILD SUCCESSFUL`. Gradle runs use `--no-daemon` when agents run in parallel; never `./gradlew --stop`.

## Review Focus

1. **A typo'd field in a project file** (`"titel"`) → Jackson 3 ignores unknown properties by default, so the project would load with a null title. Expected: startup fails naming the file and field. Test: `ProjectLibraryTest.rejectsUnknownField` (Task 2).
2. **A query of only punctuation, emoji or stop words** (`"!!!"`, `"vreau să"`) → expected: all projects, no 500. Test: `ProjectSearchTest.punctuationOnlyListsAll` (Task 3). A query over 200 chars → 400 `VALIDATION_FAILED`: `ProjectControllerTest.rejectsTooLongQuery` (Task 5).
3. **Upper case and inflected Romanian** (`"TERMOMETRUL"`, `"Ştaţie"` with cedilla) → expected: found like the lower-case base word. Tests: `ProjectSearchTest.upperCaseAndArticleSuffixMatch`, `cedillaFormsAreStripped` (Task 3).
4. **Units under maintenance, and `end` before `start`** → expected: maintenance units not counted; bad slot → 400. Tests: `ProjectServiceTest.countsOnlyAvailableUnits`, `rejectsEndNotAfterStart` (Task 4).
5. **Files inside the Boot jar** → a `File`-based scan works in the IDE but not in the jar. Expected: loading uses `ResourcePatternResolver` + `Resource.getInputStream()`. Test: `ProjectLibraryTest.loadsShippedProjectsFromClasspath` uses the production pattern `classpath*:projects/*.json` (Task 2); manual check in Task 6 (`bootJar` + `java -jar`).

---

### Task 1: PR 1, spec + dependency rule + doc 38

**Files:**
- Already written: `docs/superpowers/specs/2026-11-09-project-assistant-design.md`, this plan
- Modify: `AGENTS.md` §5 "Allowed dependencies" block (lines ~165-175)
- Create: `documentation/Necula_Matei/38-project-assistant-design.md`
- Modify: `documentation/Necula_Matei/README.md` (index row 38), `documentation/Necula_Matei/02-files-explained.md` (rows for `docs/superpowers/specs/` + `docs/superpowers/plans/`)

- [ ] **Step 1:** In AGENTS.md §5, add to the dependency block the line `catalog.api, reservation.api <- project` and after the block: `project (Matei, #140) is a leaf: nothing depends on it.`
- [ ] **Step 2:** Write doc 38 (format of doc 37: header with Role/Date/Branch/See also; Part 1 in simple terms: what the feature is and why no AI, numbered; Part 2: decisions table from spec §2, who-does-what table spec §3, files table, open asks on #140).
- [ ] **Step 3:** Add README index row `| 38 | [38-project-assistant-design.md](38-project-assistant-design.md) | Projects feature: design and plan | (in review) |` and the 02 rows.
- [ ] **Step 4:** Commit `docs(project): add project assistant design and plan`, push `docs/project-assistant-spec`, `gh pr create --base main` (body: `Refs #140`, summary, the asks to Calin/Liviu/Stefania). CI `ci` green.

### Task 2: Domain records + `ProjectLibrary` (load and validate)

**Files:**
- Create: `backend/src/main/java/com/campus/project/package-info.java` (one-line Javadoc: module purpose, owner, #140)
- Create: `backend/src/main/java/com/campus/project/domain/{LocalizedText,Keywords,Difficulty,Lang,ProjectComponent,ProjectStep,Project}.java`
- Create: `backend/src/main/java/com/campus/project/service/ProjectLibrary.java`
- Create: `backend/src/main/resources/projects/{thermometer,oscilloscope-pwm,multimeter-basics}.json`
- Create: `backend/src/test/resources/projects-invalid/<case>/*.json` (one folder per case)
- Test: `backend/src/test/java/com/campus/project/service/ProjectLibraryTest.java`

**Interfaces:**
- Produces:
  - `record LocalizedText(String ro, String en) { String in(Lang lang); }`
  - `record Keywords(List<String> ro, List<String> en) { List<String> all(); }` (both lists concatenated)
  - `enum Difficulty { BEGINNER, INTERMEDIATE, ADVANCED }`
  - `enum Lang { RO, EN; static Lang parse(String value); }` (case-insensitive; `null`/blank → `EN`; other → `BadRequestException("VALIDATION_FAILED", "lang must be ro or en")`)
  - `record ProjectComponent(String model, int quantity, LocalizedText why)`
  - `record ProjectStep(LocalizedText title, LocalizedText text)`
  - `record Project(String slug, Difficulty difficulty, int estimatedHours, LocalizedText title, LocalizedText summary, Keywords keywords, List<ProjectComponent> components, List<LocalizedText> bringYourself, List<ProjectStep> steps, List<LocalizedText> mistakes)`
  - `@Component class ProjectLibrary { ProjectLibrary(JsonMapper mapper); ProjectLibrary(JsonMapper mapper, String locationPattern); List<Project> all(); Optional<Project> find(String slug); }`. Production pattern constant `"classpath*:projects/*.json"`. Loads in the constructor; `all()` is immutable, ordered by slug.

- [ ] **Step 1: Write the failing tests** in `ProjectLibraryTest` (plain JUnit, `new ProjectLibrary(JsonMapper.builder().build(), "classpath:projects-invalid/<case>/*.json")`):
  - `loadsShippedProjectsFromClasspath`: production pattern → `all()` has 3 projects, slugs `multimeter-basics, oscilloscope-pwm, thermometer`.
  - `findsBySlug` / `unknownSlugIsEmpty`.
  - `rejectsUnknownField` (`"titel"`), `rejectsEmptyTranslation` (`title.en = ""`), `rejectsDuplicateSlug`, `rejectsBadSlug` (`"Thermo Meter"`), `rejectsZeroQuantity`, `rejectsDuplicateModel`, `rejectsMoreThanFiveUnits` (3 + 3), `rejectsNoSteps`, `rejectsNoKeywordsInOneLanguage`, `rejectsInvalidJson`.
  - Each failure test: `assertThatThrownBy(...).isInstanceOf(IllegalStateException.class).hasMessageContaining("<file>.json").hasMessageContaining("<field>")`, e.g. `"title.en"`, `"components[1].model"`, `"slug"`.
- [ ] **Step 2: Run** `./gradlew test --tests ProjectLibraryTest` → FAIL (classes missing).
- [ ] **Step 3: Implement** records and `ProjectLibrary`. Read with `mapper.readerFor(Project.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)` (comment: Jackson 3 ignores unknown fields by default; a typo must stop startup). Find files with `new PathMatchingResourcePatternResolver().getResources(pattern)`, read via `getInputStream()` (comment: works inside the Boot jar, `getFile()` does not). Validation rules = spec §5.2; message format `"projects/<filename>: <field path> <problem>"`, wrapped in `IllegalStateException`. Zero files found → `IllegalStateException` too.
- [ ] **Step 4: Write the 3 project files** (RO + EN, real content, models exactly as in spec §5.3):
  - `thermometer`: BEGINNER, 3 h; `Arduino Uno R3`×1, `DS18B20 temperature sensor kit`×1, `16x2 I2C LCD module kit`×1, `Breadboard + jumper wire kit`×1; keywords ro `termometru, temperatura, senzor, grade`, en `thermometer, temperature, sensor, degrees`; bring: USB-B cable, 4.7 kΩ resistor; ≥ 5 steps; ≥ 3 mistakes (no pull-up resistor, wrong pin, reading too often).
  - `oscilloscope-pwm`: INTERMEDIATE, 2 h; `STM32F407G-DISC1`×1, `DS1054Z`×1; keywords ro `osciloscop, pwm, semnal, masurare`, en `oscilloscope, pwm, signal, measure`.
  - `multimeter-basics`: BEGINNER, 1 h; `Fluke 115`×1, `Breadboard + jumper wire kit`×1; keywords ro `multimetru, tensiune, curent, rezistenta, continuitate`, en `multimeter, voltage, current, resistance, continuity`.
- [ ] **Step 5: Run** `./gradlew test --tests ProjectLibraryTest` → PASS. Then `./gradlew test --tests 'com.campus.architecture.*'` → PASS (if a rule fails on the new package, stop and report: Calin's file).
- [ ] **Step 6: Commit** `feat(project): load and validate project files at startup`.

### Task 3: `ProjectSearch`

**Files:**
- Create: `backend/src/main/java/com/campus/project/service/ProjectSearch.java`
- Test: `backend/src/test/java/com/campus/project/service/ProjectSearchTest.java`

**Interfaces:**
- Consumes: `Project`, `Keywords.all()`, `LocalizedText` (Task 2).
- Produces: `@Component class ProjectSearch { SearchResult search(String query, List<Project> projects); static String normalize(String text); static List<String> tokens(String text); }`, `record ScoredProject(Project project, int score)`, `record SearchResult(List<ScoredProject> hits, boolean ambiguous)` (both records nested public in `ProjectSearch` or own files in `service/`; pick own files).

- [ ] **Step 1: Write the failing tests** (plain JUnit; build small `Project` fixtures with a helper `project(slug, enTitle, roTitle, roKeywords, enKeywords)`):
  - `exactKeywordScoresThree`: query `"termometru"` vs keyword `termometru` → score `3` (+1 if also a title word; fixture title without it).
  - `pluralFindsSingularByPrefix`: `"termometre"` → score `2`.
  - `shortWordsDoNotPrefixMatch`: `"pwm"` vs keyword `pwms` → 0 (both must be ≥ 5).
  - `diacriticsAreStripped`: `"temperatură"` matches keyword `temperatura` → 3; and keyword `stație` matches query `statie`.
  - `cedillaFormsAreStripped`: `"ştaţie"` → normalizes to `statie`.
  - `upperCaseAndArticleSuffixMatch`: `"TERMOMETRUL"` → 2 (prefix `termo…` 5+).
  - `bothLanguagesAlwaysSearched`: English query `"thermometer"` finds a project whose RO keywords don't contain it but EN do.
  - `titleWordAddsOne`: token equals a title word and a keyword → 4.
  - `repeatedWordCountsOnce`: `"senzor senzor"` → same score as `"senzor"`.
  - `stopWordsAreIgnored`: `"vreau sa construiesc un termometru"` → same hits as `"construiesc termometru"`.
  - `emptyQueryListsAllByEnglishTitle` and `punctuationOnlyListsAll` (`"!!!"`, `"🙂"`): all projects, scores 0, `ambiguous = false`.
  - `noMatchReturnsEmpty`: `"submarin"` → `hits` empty.
  - `ranksByScoreThenEnglishTitle`.
  - `ambiguousWhenTopTwoWithinOne` (3 vs 2 → true) and `notAmbiguousWhenGapIsTwo` (4 vs 2 → false), `notAmbiguousWithOneHit`.
- [ ] **Step 2: Run** `./gradlew test --tests ProjectSearchTest` → FAIL.
- [ ] **Step 3: Implement.** `normalize`: `Normalizer.normalize(text.toLowerCase(Locale.ROOT), Form.NFD)` then remove `\p{M}` (cedilla and comma-below both decompose to a combining mark). `tokens`: split on `[^\p{L}\p{N}]+`, drop blanks, length < 2, and the stop-word set (spec §6, stored already normalized), then `distinct()`. Score per token = best keyword part (3 / 2 / 0) + title part (1 if the token is in the tokens of `title.ro` or `title.en`). Stop words: RO `vreau sa un o pentru de la cu si care imi este din`, EN `i want to build make a an the for with my of and`. Each constant gets a one-line *why* comment.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(project): add bilingual project search with prefix matching`.

### Task 4: `ProjectService` (search + details + availability)

**Files:**
- Create: `backend/src/main/java/com/campus/project/service/ProjectService.java`
- Create: `backend/src/main/java/com/campus/project/web/{ProjectListItem,ProjectSearchResponse,ComponentView,StepView,ProjectDetailsResponse}.java`
- Test: `backend/src/test/java/com/campus/project/service/ProjectServiceTest.java`

**Interfaces:**
- Consumes: `ProjectLibrary`, `ProjectSearch`, `Lang` (Tasks 2-3); `CatalogApi.findAvailableByModel(String model, Long excludeAssetId)`, `ReservationApi.isAssetFree(Long assetId, LocalDateTime start, LocalDateTime end, Long ignoreReservationId)`.
- Produces:
  - `record ProjectListItem(String slug, String title, String summary, Difficulty difficulty, int estimatedHours, int componentCount, int score)`
  - `record ProjectSearchResponse(boolean ambiguous, List<ProjectListItem> items)`
  - `record ComponentView(String model, int quantity, String why, int available)`
  - `record StepView(String title, String text)`
  - `record ProjectDetailsResponse(String slug, String title, String summary, Difficulty difficulty, int estimatedHours, List<ComponentView> components, List<String> bringYourself, List<StepView> steps, List<String> mistakes, boolean slotChecked, boolean reservable)`
  - `@Service class ProjectService { ProjectSearchResponse search(String query, Lang lang); ProjectDetailsResponse details(String slug, Lang lang, LocalDateTime start, LocalDateTime end); }`

- [ ] **Step 1: Write the failing tests** (Mockito, real `ProjectSearch`, mocked `ProjectLibrary`, `CatalogApi`, `ReservationApi`; `START = LocalDateTime.of(2026, 11, 16, 10, 0)`, `END = START.plusHours(2)`):
  - `searchReturnsChosenLanguage`: `Lang.RO` → `title` is the RO text; `EN` → EN.
  - `searchPassesAmbiguityThrough`.
  - `detailsWithoutSlotCountsAvailableUnits`: model with 3 `AssetSummary` → `available = 3`, `slotChecked = false`; `verifyNoInteractions(reservationApi)`.
  - `countsOnlyAvailableUnits`: catalog returns 2 (maintenance units are already excluded by `findAvailableByModel`) → `available = 2`; documents that we rely on Liviu's status filter.
  - `detailsWithSlotCountsFreeUnits`: 3 units, `isAssetFree` true/false/true → `available = 2`, `slotChecked = true`.
  - `missingModelIsZeroAndNotReservable`: catalog returns empty → `available = 0`, `reservable = false`.
  - `reservableWhenEveryComponentHasEnough` and `notReservableWhenQuantityExceedsAvailable` (quantity 2, available 1).
  - `unknownSlugIs404`: `NotFoundException` with code `PROJECT_NOT_FOUND`.
  - `rejectsHalfSlot` (only `start`) and `rejectsEndNotAfterStart` (`end == start`): `BadRequestException` code `VALIDATION_FAILED`.
- [ ] **Step 2: Run** `./gradlew test --tests ProjectServiceTest` → FAIL.
- [ ] **Step 3: Implement** with `@RequiredArgsConstructor`, `@Transactional(readOnly = true)` on both public methods. `findAvailableByModel(model, null)`: comment that `null` means "exclude nothing" (Spring Data turns `IdNot(null)` into `IS NOT NULL`; verified in Task 6). Comment on `isAssetFree`: Calin's check, counts other students' bookings and holds.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(project): add project details with live availability`.

### Task 5: `ProjectController` + contract

**Files:**
- Create: `backend/src/main/java/com/campus/project/web/ProjectController.java`
- Test: `backend/src/test/java/com/campus/project/web/ProjectControllerTest.java` (`@WebMvcTest(ProjectController.class) @Import(SecurityConfig.class)`, `@MockitoBean ProjectService`, same setup as `catalog/web/AssetControllerTest`)
- Modify: `docs/api-contract.md` (new section "Projects (Matei)" after the reservation sections: endpoints, query params, JSON examples, error codes `PROJECT_NOT_FOUND` 404, `VALIDATION_FAILED` 400)

**Interfaces:**
- Consumes: `ProjectService`, `Lang.parse` (Tasks 2, 4).
- Produces: `GET /api/v1/projects?q=&lang=` → `ProjectSearchResponse`; `GET /api/v1/projects/{slug}?lang=&start=&end=` → `ProjectDetailsResponse`.

- [ ] **Step 1: Write the failing tests:** `requiresLogin` (no user → 401, `$.code == "UNAUTHORIZED"`), `searchReturns200` (`@WithMockUser`, `$.items[0].slug`), `badLangIs400` (service not called; `Lang.parse` throws → `$.code == "VALIDATION_FAILED"`), `rejectsTooLongQuery` (201 chars → 400 `VALIDATION_FAILED`), `unknownSlugIs404` (`$.code == "PROJECT_NOT_FOUND"`), `detailsPassesSlotThrough` (`start=2026-11-16T10:00:00&end=2026-11-16T12:00:00` → `verify(service).details("thermometer", Lang.EN, START, END)`).
- [ ] **Step 2: Run** `./gradlew test --tests ProjectControllerTest` → FAIL.
- [ ] **Step 3: Implement:** `@RestController @RequestMapping("/api/v1/projects") @Validated @RequiredArgsConstructor`; `q` as `@RequestParam(required = false) @Size(max = 200) String q`; `lang` as `@RequestParam(required = false) String lang` → `Lang.parse(lang)`; `start`/`end` `@DateTimeFormat(iso = ISO.DATE_TIME)`. `@Operation` + `@Tag(name = "Projects")` on each method (401/403/ProblemDetail come from the global customizer, #184). Comment: no `/admin` prefix because students use it; covered by the `/api/v1/**` authenticated rule.
- [ ] **Step 4: Run** → PASS. Write the api-contract section.
- [ ] **Step 5: Commit** `feat(project): expose project search and details endpoints`.

### Task 6: Verify end to end, doc 39, PR 2

**Files:**
- Create: `documentation/Necula_Matei/39-project-engine.md`
- Modify: `documentation/Necula_Matei/02-files-explained.md` (rows for every new file), `documentation/Necula_Matei/README.md` (row 39)

- [ ] **Step 1:** `cd backend && ./gradlew spotlessApply build` → `BUILD SUCCESSFUL` (local PG on :5432).
- [ ] **Step 2: Real app check** (own DB `campus_projects_check`, port 8092): `bootRun`, log in as `student1@campus.test`, then:
  - `GET /api/v1/projects?q=termometre&lang=ro` → first item `thermometer`.
  - `GET /api/v1/projects/oscilloscope-pwm` → `DS1054Z` `available = 3`, `STM32F407G-DISC1` `available = 3` (proves `findAvailableByModel(model, null)` returns rows; if 0, switch to an id that can't exist and add that fix comment + test).
  - `GET /api/v1/projects/thermometer` → `reservable = false` (models not seeded yet).
  - `GET /api/v1/projects/nope` → 404 `PROJECT_NOT_FOUND`. Drop the DB afterwards.
- [ ] **Step 3: Jar check:** `./gradlew bootJar` then `java -jar build/libs/*.jar` against the same DB starts without `ProjectLibrary` errors (Review Focus 5).
- [ ] **Step 4:** Write doc 39 (Part 1 simple: what a student can do now, how search finds "termometre"; Part 2: files table, the scoring constants and why, startup validation, availability via Calin's/Liviu's facades, tests + results, real-app check output).
- [ ] **Step 5:** Commit doc + 02 + README rows; push `feat/project-engine`; `gh pr create --base main --title "feat(project): add project library, search and details endpoints"`, body `Refs #140`, test results, what's next (content, pages, Reserve all after Calin's `bookBundle`).

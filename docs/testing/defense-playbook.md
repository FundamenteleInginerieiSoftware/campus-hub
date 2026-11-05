# Defense playbook: find and fix what was broken

At the defense the professor may break something in our code and ask us to fix it. This page is the plan: how our tests point at the problem, what to run, in which order, and how we practise before the defense.

Calin's `docs/testing/test-plan.md` says **what** we test. This page says **how to use the tests when something is broken**.

---

## 1. First 2 minutes: what changed?

Most "breaks" are an edit to a file. Git shows it faster than any test.

| Situation | Command | What you see |
|---|---|---|
| Edited, not committed | `git status` then `git diff` | The exact lines that changed |
| Committed on top of ours | `git log --oneline -5`, then `git show HEAD` | The last commits and their diff |
| Anything vs. the last good state | `git diff origin/main`, or vs. a tag: `git diff v0.1.0-prototype` (later `v0.1.0`, `v0.2.0`) | Every difference from that version |
| Many commits, no idea which one | `git bisect start`, `git bisect bad`, `git bisect good <last good commit or tag>`, then `git bisect run sh -c 'cd backend && ./gradlew test --tests <FailingTest> --rerun'` (the test must exist in both commits) | The first commit where that test fails |

Understand the diff before you revert it: the question at the defense is "why did this break?", not only "can you undo it?".

## 2. Run the tests and read the first red one

```bash
cd backend
./gradlew test --rerun      # --rerun: without it Gradle may say UP-TO-DATE and run nothing
```

- **Start with `DemoFlowIT`.** It runs the whole demo through the HTTP API. Its failure message names the step, the owner and the class to open, e.g. `Step 6 (incident, Stefania): POST /api/v1/incidents should return 201; see IncidentController/IncidentService`.
- **Then the first failing unit test.** The method name states the broken rule (`rejectsOverlappingReservation`), and the `.as(...)` message says where the rule lives.
- **ArchUnit red** (`architecture/`): someone imported another module's internals or used `LocalDateTime.now()` without the `Clock`. The message names the class.
- Report with every failure: `backend/build/reports/tests/test/index.html`.

## 3. Symptom → likely cause

| Symptom | Likely cause | Where to look | Owner |
|---|---|---|---|
| App doesn't start, `Connection refused` | PostgreSQL is down | `pg_isready -h localhost`; on WSL `sudo service postgresql start` | Everyone |
| App doesn't start, `Validate failed: Migrations have failed validation` / checksum mismatch | A migration on `main` was edited | `git diff -- backend/src/main/resources/db/migration`; revert it, changes go in a **new** migration | Migration's author |
| App doesn't start, `Schema-validation: missing column` | Entity field and migration disagree (`ddl-auto: validate`) | The entity named in the error vs. its `CREATE TABLE` | Entity's owner |
| App doesn't start, `Could not resolve placeholder` / YAML error | `application*.yml` edited (indentation, a renamed key) | `git diff -- backend/src/main/resources` | Matei |
| Every request answers 401 / 403 | `SecurityConfig` rule changed, JWT secret changed, token from before a restart with a new secret | `SecurityConfigTest`, `SecurityPassIT`; log in again | Matei |
| 500 with no `code` in the body | An exception that `GlobalExceptionHandler` doesn't map | App log: first `Caused by` | Liviu (handler) + the module that threw |
| Booking accepted twice for the same slot | Overlap check or row lock broken | `ReservationService`, `CatalogApi.lockForBooking` | Calin / Liviu |
| Incident reported but bookings not moved | Event not published, listener not running, strategy broken | `AssetDamagedEvent` publish in `incident`, listener + strategies in `reassignment` | Stefania / Calin |
| Data looks wrong but code is unchanged | Someone edited rows in the database | pgAdmin; or reset (below), the dev seeders refill it | Everyone |
| Frontend blank or API calls fail | Backend down, proxy, a renamed field in a response record | Browser dev tools → Network; `npm run build` for type errors | Stefania + page owner |

**Reset the local database** (stop the app first): `DROP DATABASE campus WITH (FORCE); CREATE DATABASE campus OWNER campus;` then `./gradlew bootRun`. Flyway rebuilds the tables and the seeders add the dev data.

**Health and logs:** <http://localhost:8080/actuator/health>; in the app log, read the **first** `Caused by:`, not the last line.

## 4. Writing tests that explain themselves

These rules make a red test say what broke. They are also in `AGENTS.md` §5.

| Rule | Example |
|---|---|
| One business rule per test, named after the rule | `rejectsBookingThatEndsBeforeItStarts()` |
| The assertion message says the rule and where it lives | `assertThat(status).as("overlap: 14-16 must block 15-17; see ReservationService.createReservation").isEqualTo(409)` |
| On a wrong HTTP status, put the response body in the message | the ProblemDetail `code` explains most failures |
| Fixed `Clock`, dates relative to it, never a hard-coded calendar day | issue #94: a test broke the day its date passed |
| Every bug fix starts with a failing test | the test is the proof at the defense |

## 5. Coverage vs. mutation testing

| Tool | Answers | Our target |
|---|---|---|
| **JaCoCo** (runs with every `build`) | "Did a test **run** this line?" | ~70% on `service/`, becomes a gate in W10. **Not 100%**: the last 30% is getters, records and impossible branches, and tests written only for coverage check nothing |
| **PIT** (`./gradlew pitest`, on demand) | "If someone **breaks** this line, does a test fail?" It makes small breaks itself (`<` → `<=`, delete a call, return `null`) and lists the ones no test noticed (**survived mutants**) | Before W10, each owner opens the report for their module and adds tests for survivors that are real rules. Ignore noise (logging, `toString`) |

PIT report: `backend/build/reports/pitest/index.html`. PIT is the closest thing to "the professor breaks something": every survived mutant is a break we would not have caught.

## 6. Break-it drill (W11, before the rehearsals)

Practise the real thing once, so nobody sees this for the first time at the defense.

1. Each member makes **one** realistic break in another member's module on a local throwaway branch (never pushed). Ideas: flip a comparison, delete a `@Transactional`, remove a check, change a status code, edit a migration, change a YAML key.
2. The owner gets the branch and has **10 minutes**: sections 1–3 of this page only.
3. Write down in `docs/meetings/` (create the folder if it is missing): the break, how it was found (git, which test, which message), the time it took.
4. **If no test caught it, write that test** (in the owner's module, normal PR).

| Breaks | Whose module |
|---|---|
| Matei | Liviu (catalog, common) |
| Liviu | Calin (reservation, reassignment) |
| Calin | Stefania (incident, frontend) |
| Stefania | Matei (security, auth, config) |

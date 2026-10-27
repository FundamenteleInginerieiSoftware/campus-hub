# ArchUnit library for Calin's architecture tests (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-27
**Branch / PR:** `chore/build-archunit`
**See also:** `docs/plan-w4-w6.md` (W4 tasks), `AGENTS.md` §5 (allowed dependencies between modules)

---

## Part 1: In simple terms

Our app is split into modules (catalog, reservations, incidents, ...) that may only talk to each other through their public `api` package. Calin writes tests that check this rule automatically, so nobody breaks it by accident. Those tests need a library called ArchUnit.

1. **Only I edit `build.gradle`** (the file that lists our libraries), so adding it is my job.
2. **Added ArchUnit 1.5.1**, the newest version, for tests only. It doesn't end up in the running app.
3. **Checked that it works with Java 25:** a throwaway test read all our classes and checked one sample rule. Both worked, and I deleted the test again (the real rules are Calin's).

**Result:** Calin can write his module rules in `src/test/.../architecture/` without waiting.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/build.gradle` | `testImplementation 'com.tngtech.archunit:archunit-junit5:1.5.1'` + a comment |

### Why like this

| Choice | Why |
|---|---|
| `testImplementation` | ArchUnit only runs in tests; it reads the compiled `.class` files. It's not packaged into the app jar. |
| Explicit version `1.5.1` | Spring Boot's BOM doesn't manage ArchUnit, so Gradle needs a version (same as springdoc). 1.5.1 is the latest on Maven Central. |
| `archunit-junit5` (not plain `archunit`) | Adds `@AnalyzeClasses` + `@ArchTest`, so rules run as normal JUnit 5 tests inside `./gradlew test`. |

### How it connects

- **Calin:** writes the module-boundary rules (W4, freeze mode first, strict after M1). Example: catalog must not depend on reservation; only `api` packages are imported across modules.
- **Everyone:** once the rules are in, `./gradlew build` (and the `ci` check) fails if someone imports another module's `domain`, `service`, `repository` or `web` package.

### Tests

| Check | Result |
|---|---|
| Throwaway `ArchUnitSmokeTest` (not committed): import `com.campus` without tests, rule "catalog doesn't depend on reservation" | 53 classes imported, rule passed: ArchUnit 1.5.1 reads Java 25 class files |
| `./gradlew spotlessApply build` | BUILD SUCCESSFUL, 101 tests green (same as `main`; no new test in this PR) |

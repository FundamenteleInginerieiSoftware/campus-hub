# Security test probe paths (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-29
**Branch / PR:** `test/config-security-probes` (issue #67, reported by Liviu)
**See also:** `05-security-error-json.md` (where `SecurityConfigTest` checks the 401/403 JSON), `AGENTS.md` §5 (`/api/v1/admin/**` is admin-only)

---

## Part 1: In simple terms

`SecurityConfigTest` checks the "doors" of the app: who may open which page. To test only the door, it knocks on pages that don't exist. If the door opens, the app answers "page not found" (404), and the test knows security let the request through.

1. **The problem:** the test knocked on `/api/v1/assets` and `/api/v1/admin/assets`. Those were empty in W3, but Liviu is now building real asset pages there, so the app answered "wrong method" (405) or "here is the list" (200) instead of 404, and the test failed even though security was fine.
2. **The fix:** the test now knocks on two made-up pages, `/api/v1/_security-probe` and `/api/v1/admin/_security-probe`, that no module will ever build.
3. **Nothing changes in the app**, only in the test.

**Result:** Liviu can merge his asset CRUD, and teammates' new endpoints can no longer break the security test by accident.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/src/test/java/com/campus/config/SecurityConfigTest.java` | Constants `PROBE` + `ADMIN_PROBE` (with a bug-fix comment) used in the 5 tests that called `/api/v1/assets` or `/api/v1/admin/assets` |

### Why like this

| Choice | Why |
|---|---|
| Fake paths, not real ones | The test checks `SecurityConfig`, not controllers. With an unmapped path, the only possible answers are 401/403 (security blocked) or 404 (security let it through), so the result can't depend on someone else's code. |
| `_security-probe` name | A leading underscore isn't used by our REST naming (plural nouns), so nobody maps it by accident, and the name says what it is for. |
| Also changed the 401 tests | They still passed with `/api/v1/assets` (401 comes before any controller), but the `$.instance` check would have tied them to Liviu's module. Same paths everywhere is simpler. |

### How it connects

- **Liviu:** his W4 asset CRUD (`GET /api/v1/admin/assets`, W5 `GET /api/v1/assets` search) no longer breaks this test. He rebases once this is on `main`.
- **Everyone:** the rules being tested are unchanged: no token gives 401, a STUDENT on `/api/v1/admin/**` gives 403, an ADMIN gets through, paths outside `/api/v1/` give 403.

### Tests

| Check | Result |
|---|---|
| `./gradlew spotlessApply build` | BUILD SUCCESSFUL, all tests green (same count as `main`; no new test, 5 tests changed) |

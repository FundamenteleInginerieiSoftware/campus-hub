# Tests for the gaps mutation testing found (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-06
**Branch / PR:** `test/user-config-mutation-gaps` (PIT run on `99d0261`)
**See also:** `18-prod-admin-bootstrap.md` (first admin), `19-dockerfile.md` (`SpaConfig`), `17-users-me.md` (`/users/me`)

---

## Part 1: In simple terms

1. **Mutation testing (PIT)** changes our code on purpose, one small thing at a time (for example `<` becomes `<=`), and runs the tests again. If every test still passes, that change "survived": the line could break and nobody would notice.
2. **In my code, 5 such gaps were found:**
   - **First admin on AWS:** no test used a password of exactly 8 characters or exactly 72 bytes, the two edges of the allowed length.
   - **Website on AWS:** no test checked what happens when the website build is missing, and no test asked for a real file directly.
   - **"Who am I?" (`/users/me`):** only the slow full-app tests reached it, and PIT leaves those out.
3. **The fix is tests only, no app code changed:** 9 new tests that pin each of these edges.
4. **Each test was checked against its gap:** I made the same small change PIT made by hand, saw the new test go red, and put the code back.

**Result:** the 5 gaps are closed; if someone changes one of these lines by mistake, a test now fails.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `test/.../user/service/AdminBootstrapTest.java` | +3 tests: exactly 8 characters accepted, exactly 72 bytes (`"ă" × 36`) accepted, 73 bytes in 37 characters rejected |
| `test/.../config/SpaConfigTest.java` | **New**, plain unit test (no Spring) of `SpaConfig.IndexHtmlFallback.getResource`, 3 tests |
| `test/.../config/SpaFrontendTest.java` | +1 test: `GET /index.html` → 200 with the file |
| `test/.../user/web/UserControllerTest.java` | **New** `@WebMvcTest(UserController.class)` + `@Import(SecurityConfig.class)`, 2 tests |

Every new assertion has an AssertJ `.as("<rule>; see <Class.method>")`, so a red test says which rule broke and where.

### Mutant → test → proof

"Proof" = the mutant applied by hand in `src/main`, the named test class run, the code reverted (`git diff -- backend/src/main` empty at the end).

| # | Mutant (PIT) | Killed by | With the mutant |
|---|---|---|---|
| 1 | `AdminBootstrap.java:61` `length() < 8` → `<= 8` (survived) | `AdminBootstrapTest.acceptsAPasswordOfExactly8Characters` | FAILED |
| 2 | `AdminBootstrap.java:61` `bytes > 72` → `>= 72` (survived) | `AdminBootstrapTest.acceptsAPasswordOfExactly72Bytes` | FAILED |
| 3 | `SpaConfig.java:47` `index.isReadable()` negated (survived) | `SpaConfigTest.reactPageIsNotFoundWithoutAFrontendBuild` + `reactPageGivesIndexHtmlWhenTheBuildIsThere` | both FAILED; with the check removed (`return index;`), the first one FAILED |
| 4 | `SpaConfig.java:38` `return file;` (no coverage; checked as `return null;`) | `SpaConfigTest.existingFileIsServedAsItIs` + `SpaFrontendTest.existingFileIsServedDirectly` | both FAILED |
| 5 | `UserController.java:23` `return userService.me();` (no coverage; checked as `return null;`) | `UserControllerTest.meReturnsTheAccountOfTheTokensUser` | FAILED |

`AdminBootstrapTest.refusesAPasswordOver72BytesEvenWithFewCharacters` and `UserControllerTest.meWithoutATokenIs401` kill no PIT mutant; they pin the other side of each rule (bytes, not characters; 401 JSON without a token).

### Choices

| Choice | Why |
|---|---|
| Multi-byte passwords (`"ă"` = 2 bytes) | The limit is 72 **bytes** (BCrypt ignores the rest), not 72 characters. 37 characters / 73 bytes must fail, which an ASCII-only test can't show |
| `SpaConfigTest` calls the resolver directly | PIT runs many mutants in one JVM and reuses `SpaFrontendTest`'s Spring context, including the resource chain's cache (`resourceChain(true)`), so the old, unmutated answer came back. A plain unit test has no context and no cache. `IndexHtmlFallback` is package-private and `getResource` is protected, so a test in `com.campus.config` can call it |
| `@TempDir` as `static/` | One test needs a folder **without** `index.html` (dev, or a Docker build that forgot the frontend); the test classpath always has one. The location ends in `/`, like `classpath:/static/` |
| `null` from the resolver = 404 | Spring turns it into a 404; `SpaFrontendTest.missingFileIsA404NotTheReactApp` already shows that over HTTP |
| `UserControllerTest` uses `jwt()` | spring-security-test puts the token's user in directly, so `activeUserFromToken` and `UserApi` are skipped (`ObjectProvider`, `22-token-account-check.md`). Same pattern as `AuthControllerTest` |

### How it connects

| Who | Effect |
|---|---|
| `ADMIN_PASSWORD` on AWS (Terraform/SSM) | 8 characters to 72 bytes, both edges included; anything else stops the app at startup |
| Stefania's React build in the Docker image | Real files come back as they are; without a build, React pages are a plain 404 instead of an error |
| Stefania's frontend (`/users/me` after a reload) | The web layer of "who am I?" is now tested on its own, fast, without a database |

### Tests

| Check | Result |
|---|---|
| Each mutant above, applied by hand | the listed tests FAILED; code reverted |
| `./gradlew spotlessApply build --rerun` (scratch PostgreSQL 18 on :5436) | BUILD SUCCESSFUL, 443 tests, 0 failures |

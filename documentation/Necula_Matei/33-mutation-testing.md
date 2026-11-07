# Mutation testing with PIT: which rules have no test that would catch a break (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-06
**Branch / PR:** `chore/build-pitest`
**See also:** `docs/testing/test-plan.md` (team test plan), `15-archunit-dependency.md` (the other test library in `build.gradle`)

---

## Part 1: In simple terms

1. **The question:** at the defense the professor may change a line of our code and ask "would your tests notice?". Coverage (JaCoCo) only says a line *ran* during the tests, not that a test would *fail* if the line were wrong.
2. **Mutation testing answers it.** A tool (PIT) makes one small "bug" at a time in our compiled code, for example `<` becomes `<=`, a `return x` becomes `return null`, or a method call disappears. Each bugged copy is a **mutant**. Then it reruns the tests that touch that line.
   - A test fails → the mutant is **killed**: good, a break there would be caught.
   - All tests still pass → the mutant **survived**: that line could break and nobody would notice.
   - No test even runs the line → **no coverage**.
3. **Example from our code:** `AdminBootstrap` refuses an admin password shorter than 8 characters: `if (password.length() < 8 || ...)`. JaCoCo shows the line as 100% covered. PIT changed `< 8` to `<= 8` and every test still passed, because no test uses a password of *exactly* 8 characters. So a "password of 8 characters is rejected" bug would slip through. The missing test is one line.
4. **On demand only:** it reruns the tests hundreds of times, so it is not part of `build` or CI. Run it before the defense or after a big change: `./gradlew pitest` (about 3 minutes).
5. **First run:** 506 mutants, 87% killed overall. The list of survivors, per teammate, tells each of us which tests to add.

**Result:** we now know exactly which business rules have no test that would catch a break, instead of guessing from the coverage percentage.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/build.gradle` | Plugin `info.solidsoft.pitest` 1.19.0 + a `pitest { }` block (on-demand task, not wired into `build`/`check`) |
| `documentation/Necula_Matei/02-files-explained.md` | `build.gradle` rows: the plugin and the `pitest` block |
| `documentation/Necula_Matei/README.md` | Row 33 |

### Versions (Gradle 9.8.1, Java 25, Boot 4.1.1 / JUnit 6.0.3)

| Piece | Version | Why this one |
|---|---|---|
| Gradle plugin `info.solidsoft.pitest` | 1.19.0 (29 Mar 2026) | Latest release; first with Gradle 9 support |
| PIT (`pitestVersion`) | 1.30.1 (14 Oct 2026) | Bundles ASM 9.11, which reads Java 25 class files. 1.30.2 came out on 5 Nov, too fresh |
| `pitest-junit5-plugin` | 1.2.3 | Latest; runs JUnit 6 tests when the project's own launcher is used (see `addJUnitPlatformLauncher`) |

### The `pitest { }` block, setting by setting

| Setting | Why |
|---|---|
| `addJUnitPlatformLauncher = false` | The plugin would add its own older JUnit launcher, which doesn't match Boot 4's JUnit 6 and finds no tests (pitest-junit5-plugin issue #113). We already declare `junit-platform-launcher`, so the BOM version is used |
| `targetClasses = ['com.campus.*']` | All our production code. Records' generated methods are skipped by PIT itself, so no need to exclude `web/` records |
| `excludedClasses` | `CampusHubApplication`, `*Seeder` (dev data only), `AsyncConfig`, `AwsConfig`, `OpenApiConfig` (pure bean wiring) |
| `excludedMethods` | SecurityConfig's start-up `@Bean` methods (`apiChain`, `passwordEncoder`, `jwtEncoder`, `jwtDecoder`, `secretKey`, `requestRejectedHandler`, `ignoreTokenOnAuthPaths`, `activeUserFromToken`). See "Problem hit" below |
| `excludedTestClasses` | `*IT` (threads, rollbacks, full flows: slow), ArchUnit rules (read classes, never run them), `CampusHubApplicationTests`, `AsyncConfigTest`, `ProdProfileStartupTest` (only check the app starts) |
| Tests included | Unit tests, `@WebMvcTest` / `@DataJpaTest` slices and the MockMvc tests of `config/` (`SecurityConfigTest`, `ErrorJsonTest`, `SpaFrontendTest`): the only tests of `SecurityConfig` and `SpaConfig`. They proved cheap: the slowest test took 0.8 s |
| `threads = Runtime.runtime.availableProcessors()` | One test JVM per core (12 on my laptop) |
| `outputFormats = ['HTML', 'XML']`, `timestampedReports = false` | HTML to read, XML for scripts; always the same folder, so a link to it stays valid |
| `jvmArgs = ['-Dspring.profiles.active=test']` | Same `test` profile and `campus_test` database as the `test` task |

Not set on purpose: mutators (PIT's default group is enough), thresholds (no gate, it's a tool for finding gaps).

### How to run and read it

```bash
cd backend
./gradlew pitest            # PostgreSQL must be running, like for ./gradlew build
# report: build/reports/pitest/index.html   (raw data: build/reports/pitest/mutations.xml)
```

| In the HTML report | Meaning |
|---|---|
| Line coverage / mutation coverage / test strength per package | Coverage = lines run; mutation coverage = killed / all mutants; test strength = killed / mutants on covered lines |
| Green line | All its mutants killed |
| Red line + "SURVIVED" | A test runs it but none fails when it breaks: add an assertion or a boundary case |
| Red line + "NO_COVERAGE" | No included test runs it (an excluded `*IT` may still) |

`./gradlew build` does not run PIT (checked: no `build/reports/pitest` after a build).

### First run (base `99d0261`, scratch PostgreSQL 18)

| Owner | Packages | Mutants | Killed | Survived | No coverage | Mutation score | Test strength |
|---|---|---|---|---|---|---|---|
| Matei | `user`, `config` | 50 | 45 | 3 | 2 | 90% | 94% |
| Liviu | `catalog`, `common.error/paging/audit`, `storage`, `notification` | 225 | 187 | 14 | 24 | 83% | 93% |
| Calin | `reservation`, `reassignment`, `common.time` | 202 | 185 | 8 | 9 | 92% | 96% |
| Stefania | `incident`, `common.security` | 29 | 25 | 0 | 4 | 86% | 100% |
| **All** | | **506** | **442** | **25** | **39** | **87%** | **95%** |

| Runtime (12 threads) | Time |
|---|---|
| Coverage analysis | 21 s |
| Mutation analysis | 2 min 28 s |
| Whole `./gradlew pitest` | 2 min 53 s |
| `./gradlew spotlessApply build` (unchanged, PIT not in it) | 1 min 12 s |

**My survivors (to fix with new tests):**

| Where | Mutant | Missing test |
|---|---|---|
| `AdminBootstrap.run:61` | `< 8` → `<= 8`, `> 72` → `>= 72` (2 mutants) | Password of exactly 8 characters and of exactly 72 bytes must be accepted |
| `SpaConfig.IndexHtmlFallback.getResource:47` | `isReadable()` negated | No `index.html` (dev): a React path must give 404 |
| `SpaConfig.IndexHtmlFallback.getResource:38` | no coverage | `GET` of an existing static file (e.g. `/index.html`) |
| `UserController.me:23` | no coverage | Only `AuthFlowIT`/`SecurityPassIT` call `/users/me` (excluded); a `@WebMvcTest` would cover it |

All per-request security code is killed: the bearer-token resolver lambda, the active-account check, `userId(jwt)`, `SecurityErrorHandler`, `RegisterRequest`'s trim and 72-byte rule, `AuthService`, `UserService`.

The full list for all four of us (class → method:line → mutant → a guess of the missing test) is made from `mutations.xml` and goes to the team with this PR.

### Problem hit and fix

| Problem | Cause | Fix |
|---|---|---|
| First run: all 8 start-up `@Bean` methods of `SecurityConfig` "survived", even `passwordEncoder()` returning `null` | These methods run once, when Spring starts the context. PIT tests many mutants of a class in one JVM, and Spring's test context cache reuses the context started for an earlier mutant, so the mutated method never runs again. With a fresh context these mutants would stop the app, so they were false alarms. The Gradle plugin has no option to start one JVM per mutant | `excludedMethods` lists those 8 names (checked: no other class has methods with these names). Their lambdas (`lambda$activeUserFromToken$0` …) have different names, so they stay mutated |
| Gradle warns "`Configuration.setVisible` deprecated" | Comes from the plugin itself (`PitestPlugin.groovy:103`), fixed in its unreleased 1.20.0 | Harmless on Gradle 9; update the plugin when 1.20.0 is out |

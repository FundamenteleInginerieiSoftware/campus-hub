# Background work and timers (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-21
**Branch / PR:** `feat/async-scheduling`
**See also:** `02-files-explained.md` (what each file does)

---

## Part 1: In simple terms

Some work shouldn't make the user wait, and some work must happen on its own at set times. Spring can do both, but they are switched off until someone turns them on.

1. **Background work switched on.** A teammate can mark a method to run in the background, for example moving bookings to another device after a damage report, or sending an e-mail. The user's request finishes without waiting for it.
2. **Timers switched on.** A teammate can mark a method to run every few minutes, for example marking late returns.
3. **A small, named group of background workers.** Two workers, with names that show up in the logs, so we can see which lines came from background work.
4. **In tests, background work runs right away**, so a test doesn't have to guess when it's finished.
5. **Tested it.**

**Result:** Calin can write his late-return jobs and his reassignment listener.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `config/AsyncConfig.java` | New: `@EnableAsync` + `@EnableScheduling`; synchronous executor in the `test` profile |
| `application.yml` | `spring.task.execution`: thread names `campus-async-*`, pool of 2 |
| `test/.../config/AsyncConfigTest.java` | New: proves `@Async` is active and runs synchronously in tests |

### Key choices

| Choice | Why |
|---|---|
| `AsyncConfig` = only the two `@Enable...` switches | Spring Boot already builds the `@Async` thread pool; we only size and name it in `application.yml` (`thread-name-prefix: campus-async-`, `pool.core-size: 2`). No custom pool code |
| `SyncTaskExecutor` bean, `@Profile("test")` | In tests `@Async` runs on the test's thread, so Calin's reassignment tests see the result as soon as the call returns (not flaky). Defining an executor makes Boot skip its own pool in tests |

### How it connects to teammates

| Who | What they get |
|---|---|
| Calin | Can write `@Scheduled` overdue/no-show jobs and `@Async` listeners (AGENTS §9 check: `@EnableScheduling`, `@EnableAsync`) |
| Liviu / Matei | Notifications can be sent from an `@Async` listener, so a slow mail server never slows a booking |

### Testing

| Command / test | Result |
|---|---|
| `cd backend && ./gradlew spotlessApply build` | BUILD SUCCESSFUL, **24 tests** (+1) |
| `AsyncConfigTest.asyncMethodsRunOnTheTestThreadInTests` | A test-only `@Async` bean is a proxy (`@EnableAsync` works) and runs on the test thread (sync executor works) |

### Problem hit

| Problem | Fix |
|---|---|
| `AsyncConfigTest` first failed with `expected "Test worker" but was null` | The test read a **field** on the bean, but the bean is Spring's proxy, and the proxy's own copy of the field stays empty. Reading it through a **method** (`threadName()`) reaches the real object. The fix is commented in the test. Rule: never read fields of a Spring bean directly |

# Scheduler thread pool of 3 (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-22
**Branch / PR:** `chore/config-scheduler-pool` (#347)
**See also:** `LiveStreams.heartbeat()` (Liviu, #317), `ReservationLifecycleService` (Calin), `documentation/Necula_Matei/60-ses-live-push.md`

---

## Part 1: In simple terms

1. **What was wrong:** the app has small jobs that run on a timer. By default Spring runs all of them one after the other on a single worker. One of them sends a tiny "I'm still here" signal every 20 seconds to keep the live notification connection (the toasts) open. Others check for no-shows and overdue bookings, and send e-mails, which can be slow.
2. **Why it matters:** if an e-mail job is slow, the "I'm still here" signal waits. On AWS, CloudFront closes a connection that is silent for 30 seconds, so the live toasts would stop.
3. **What we did:** gave the timer jobs 3 workers instead of 1, and named them `campus-sched-` so they are easy to find in the logs.
4. **Result:** the signal always has a free worker, even when two slow jobs run at once. Nothing else changes for users. Found while listing what is left on 22 Nov (it was an optional note in the review of Liviu's #317).

---

## Part 2: Technical details

| File | Change |
|---|---|
| `backend/src/main/resources/application.yml` | `spring.task.scheduling.pool.size: 3` and `thread-name-prefix: campus-sched-`, with a comment |
| `backend/src/main/java/com/campus/config/AsyncConfig.java` | Javadoc only: mentions the scheduling pool |

No new files, no API change, no migration.

```yaml
spring:
  task:
    scheduling:
      thread-name-prefix: campus-sched-
      pool:
        size: 3
```

### Why

`@EnableScheduling` (in `AsyncConfig`) makes Spring Boot build a `ThreadPoolTaskScheduler`. Its default size is 1, so every `@Scheduled` method waits for the previous one. Boot reads the `spring.task.scheduling.*` properties for it, so no Java code is needed.

### The four jobs that share it

| Job | Owner | Rate | Notes |
|---|---|---|---|
| `LiveStreams.heartbeat()` | Liviu (#317) | every 20 s | keeps SSE streams alive; CloudFront closes idle connections after 30 s |
| `ReservationLifecycleService.runLifecycleSweep()` | Calin | every 5 min | no-show and overdue; sends e-mails (SES on `prod`), the slowest job |
| `sweepExpiredHolds()` | Calin | every minute | expires 1-minute slot holds |
| `IdempotencyService.sweepExpiredRecords()` | Calin | every 2 days | deletes old idempotency keys |

Why 3: two sweeps can overlap (the 5-minute sweep still running when the 1-minute one starts), and the heartbeat still has a third thread. The `@Async` pool (`campus-async-`) is separate and untouched.

### How it was tested

| Check | Result |
|---|---|
| `cd backend && ./gradlew spotlessApply build` | BUILD SUCCESSFUL, 1312 tests, 0 failures |
| `./gradlew bootRun`, then `jcmd <pid> Thread.print` | the running app has exactly the threads `campus-sched-1`, `-2`, `-3` (the default would be one `scheduling-1`) |

### Problems and fixes

| Problem | Fix |
|---|---|
| Not a bug in the code, a default that only hurts under load: with one thread a slow SES call delays the heartbeat | Pool of 3 plus a comment in `application.yml` saying why, so nobody shrinks it back to 1 |

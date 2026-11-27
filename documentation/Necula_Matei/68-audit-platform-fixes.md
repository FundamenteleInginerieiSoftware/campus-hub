# Audit fixes: SES async pool, live timestamp, calendar OpenAPI, DST horizon, deploy image prune (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-26
**Branch / PR:** `fix/platform-audit-bugs` (#366)
**See also:** `documentation/Necula_Matei/60-ses-live-push.md`, `documentation/Necula_Matei/62-scheduler-thread-pool.md`, `documentation/Necula_Matei/53-calendar-feed-security-rule.md`

---

## Part 1: In simple terms

A codebase bug hunt and live test pass against PostgreSQL 18 found five small issues in my own modules (prod e-mail threads, Swagger docs, project booking dates, and AWS deploy housekeeping):

1. **Slow e-mails could block live toasts and reassignments in prod:** Amazon SES e-mails shared the same 2-worker background pool as live browser toasts and automatic reassignments. If two e-mails took 20 seconds, everything else waited behind them. Now SES has its own separate pool (`campus-ses-`), and the fast background pool (`campus-async-`) has 4 workers that shut down when idle.
2. **Prod live toast timestamps had extra sub-second digits:** in prod, `SesNotificationService` sent nanoseconds on `LiveEvent.at` instead of whole seconds like dev (`LoggingNotificationService`). Now both truncate to whole seconds.
3. **Swagger showed a lock icon and 401 on the public calendar feed:** `GET /api/v1/calendar/{token}.ics` is intentionally public (calendar apps fetch it with the secret token in the URL, no JWT), but Swagger only stripped the lock icon from `/api/v1/auth/**`. Now Swagger matches `SecurityConfig`.
4. **Projects 14-day picker drifted by 1 hour across daylight-saving changes:** `ProjectDetailsPage` added `14 * 86,400,000` milliseconds instead of 14 calendar days, disagreeing with the backend's `LocalDateTime.plusDays(14)` across spring/autumn clock changes.
5. **Deploy housekeeping:** `campus-deploy` now runs `docker image prune -f` after starting the new container so old images do not fill the 20 GB EC2 disk over repeated deploys, and `sync-wiki.yml` uses `actions/checkout@v7` like the other workflows.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `backend/src/main/java/com/campus/config/AsyncConfig.java` | Explicit `@Primary` `applicationTaskExecutor` / `taskExecutor` (`@Profile("!test")`, bound from `spring.task.execution.*`) + prod-only `mailTaskExecutor` (`campus-ses-`, 8 core threads, `allowCoreThreadTimeOut=true`, graceful 10 s shutdown) |
| `backend/src/main/resources/application.yml` | `spring.task.execution.pool.core-size: 4` and `allow-core-thread-timeout: true` |
| `backend/src/main/java/com/campus/notification/service/SesNotificationService.java` | `@Async(AsyncConfig.MAIL_EXECUTOR)` on `notifyUser`; truncate `LocalDateTime.now(clock)` to `ChronoUnit.SECONDS` before `LiveEvent.notification(...)` |
| `backend/src/main/java/com/campus/config/OpenApiConfig.java` | Clear `bearerAuth` (`security: []`) and skip `401` for `GET /api/v1/calendar/*` alongside `/api/v1/auth/**` |
| `frontend/src/pages/ProjectDetailsPage.tsx` | Compute 14-day horizon via calendar-day addition (`new Date(y, m, d + 14, h, min, s)`) instead of fixed milliseconds |
| `infra/user-data.sh.tftpl` | Add `docker image prune -f || true` after starting `campus-hub` in `/usr/local/bin/campus-deploy` |
| `.github/workflows/sync-wiki.yml` | Align `actions/checkout@v4` → `@v7` with `ci.yml`, `deploy.yml`, `docs-check.yml` |
| `backend/src/test/java/com/campus/notification/service/SesNotificationServiceTest.java` | Verify `@Async(AsyncConfig.MAIL_EXECUTOR)` and whole-second truncation on sub-second clock |
| `backend/src/test/java/com/campus/config/ProdProfileStartupTest.java` | Verify prod creates distinct `campus-async-` and `campus-ses-` pools and that saturating `mailTaskExecutor` does not block `applicationTaskExecutor` |
| `backend/src/test/java/com/campus/config/OpenApiDocsIT.java` | Assert `GET /api/v1/calendar/*` (and `/api/v1/auth/**`) has `security: []` and no `401` response |

### Why explicit `applicationTaskExecutor` in `AsyncConfig`

Spring Boot's `TaskExecutionAutoConfiguration` creates `applicationTaskExecutor` with `@ConditionalOnMissingBean(Executor.class)`. Declaring `mailTaskExecutor` in `prod` causes Boot's default bean to back off, so `AsyncConfig` declares `applicationTaskExecutor` (aliased as `taskExecutor` and `@Primary`) explicitly via `ThreadPoolTaskExecutorBuilder` whenever `!test` is active. That keeps unqualified `@Async` (`LiveStreams.send`, `LocalAssetDamagedListener`, `LocalAssetRetiredListener`) on `campus-async-` while `@Async(AsyncConfig.MAIL_EXECUTOR)` isolates SES on `campus-ses-`.

### Verification

- `cd backend && ./gradlew spotlessApply build --no-daemon`
- `cd frontend && npm run lint && npm run build`

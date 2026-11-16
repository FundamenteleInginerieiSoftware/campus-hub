# Calendar feed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A student creates a secret link once; any calendar app that subscribes to it shows the student's lab bookings and follows cancellations and reassignments.

**Owner:** Calin (split of 10 Nov: each new feature goes to the member who owns its data; proposed until the team agrees). This plan is a guide: he writes the code. Matei writes only the `SecurityConfig` rule (Task 0).

**Architecture:** Everything lives in Calin's `reservation` module. A new table `calendar_feeds` holds one SHA-256 token hash per user. `CalendarTokenService` creates, replaces and revokes the link and turns a token into an active user id (`UserApi.findActiveRole`). `CalendarFeedService` reads that user's reservations from his own `ReservationRepository`, the asset names from Liviu's `CatalogApi`, and writes iCalendar text with our own `IcsWriter`. One `permitAll` rule in Matei's `SecurityConfig` opens the feed path.

**Tech Stack:** Spring Boot 4.1.1, Java 25, Flyway, JPA, `java.security` (`SecureRandom`, `MessageDigest`), JUnit 5 + AssertJ + Mockito, `@DataJpaTest`, `@WebMvcTest`; React 19 + Mantine 9 + TypeScript.

**Spec:** `docs/superpowers/specs/2026-11-10-calendar-feed-design.md`

## Global Constraints

- Never edit a teammate's file (AGENTS.md §3): not `SecurityConfig` / `SecurityConfigTest` (Matei), not `User` / `users` (Matei), not `App.tsx` / `navigation.ts` without Stefania's review. Task 4 starts only when Matei's rule (Task 0) is on `origin/main`.
- New classes import from other modules only `com.campus.user.api.*`, `com.campus.catalog.api.*`, `com.campus.common.*`. Packages `domain`, `repository`, `service`, `web` of `reservation`. No new module.
- Token: 32 bytes `SecureRandom`, base64url **without padding** (43 chars); accepted pattern `[A-Za-z0-9_-]{43}`. Stored: SHA-256 of the token's UTF-8 bytes, 64 lowercase hex chars, column `calendar_feeds.token_hash VARCHAR(64) NOT NULL UNIQUE`; `user_id BIGINT NOT NULL UNIQUE REFERENCES users(id)`.
- Feed constants: `PAST_DAYS = 30`, `FUTURE_DAYS = 30`; shown statuses `CONFIRMED, CHECKED_OUT, OVERDUE, RETURNED`; `UID` = `reservation-<id>@campus-hub`; `PRODID:-//Campus Hub//Reservations//EN`; calendar name `Campus Hub`; fold at 75 **bytes**; CRLF line ends.
- Paths: settings `/api/v1/reservations/me/calendar-feed`; feed `/api/v1/calendar/{token}.ics`.
- Errors: `NotFoundException("CALENDAR_FEED_NOT_FOUND", "Calendar link not found")`. Audit actions `CALENDAR_FEED_CREATED`, `CALENDAR_FEED_REVOKED`, entity type `USER` + the user's id.
- The migration is a **new** file named with the time it is written (`V2026_MM_DD_HHMM__reservation_create_calendar_feeds.sql`); before merging, check no later migration landed on `main` (AGENTS.md §4).
- "Now" and the zone only from the injected `Clock`. DTOs are `record`s in `web/`. `@Transactional` on public service methods only.
- Tests: one rule per test, `.as("<rule>; see <Class.method>")`, fixed `Clock`. Comments only for hidden behaviour, *why*, links to teammates' code. Commits with your own git identity, **no AI trailer**. Each PR ships its personal doc (`documentation/Murariu_Calin/NN-…md`) + its files-explained/README rows.
- Before each push: `cd backend && ./gradlew spotlessApply build` → `BUILD SUCCESSFUL`; frontend `npm run lint && npm run build`.

## Review Focus

1. **An asset name with a comma, semicolon or Romanian letters** (`"Sursă DP832, canal 2; nouă"`) → the event title must show exactly that text, and a long one must fold without cutting `ă` in half. Tests: `IcsWriterTest.escapesSpecialCharacters`, `foldsWithoutSplittingAMultiByteLetter` (Task 3).
2. **A booking in summer time** → the event must sit at the right hour in July too (UTC+3), not one hour off. Test: `IcsWriterTest.convertsSummerTimeToUtc` (Task 3).
3. **A browser that still has an expired login token and opens the link** → 200, not 401 (#116 class). Test: `SecurityConfigTest.calendarFeedIgnoresStaleToken` (Task 0, Matei).
4. **Garbage in the token position** (`../x`, 5,000 characters, `%00`) → 404 `CALENDAR_FEED_NOT_FOUND` (or the firewall's 400), never a 500 and never a database call. Tests: `CalendarTokenServiceTest.malformedTokenNeverReachesTheDatabase` (Task 2), `CalendarFeedControllerTest.oddTokenIs404` (Task 4).
5. **A user with no bookings at all** → a valid, empty calendar (apps reject an empty body). Test: `IcsWriterTest.emptyCalendarIsValid` (Task 3).

---

### Task 0 (Matei): open the feed path in `SecurityConfig`

Matei's own small PR `feat/config-calendar-feed-public`, independent of Calin's work; it should land first.

- [ ] `SecurityConfigTest`: `calendarFeedIsPublic` (GET `/api/v1/calendar/<43 chars>.ics` without a token is not 401; nothing is mapped yet, so 404 is the expected answer); `calendarFeedIgnoresStaleToken` (`Authorization: Bearer not-a-token` → not 401); `postOnCalendarPathStillNeedsLogin` (401); `otherApiPathsStillNeedLogin` (the existing probe path → 401). Run → FAIL.
- [ ] `SecurityConfig`: `.requestMatchers(HttpMethod.GET, "/api/v1/calendar/*").permitAll()` above the `/api/v1/**` rule (comment: the token in the path is the key, Calin's `CalendarFeedController`); the bearer-token resolver also skips `/api/v1/calendar/**` (same reason as #116). Run → PASS; `./gradlew spotlessApply build`.
- [ ] api-contract §2 row; personal doc in `documentation/Necula_Matei/`. Commit `feat(config): open the calendar feed path`.

### Task 1: `calendar_feeds` table, entity, repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V2026_MM_DD_HHMM__reservation_create_calendar_feeds.sql`
- Create: `backend/src/main/java/com/campus/reservation/domain/CalendarFeed.java`, `backend/src/main/java/com/campus/reservation/repository/CalendarFeedRepository.java`
- Test: `backend/src/test/java/com/campus/reservation/repository/CalendarFeedRepositoryTest.java`

**Interfaces:**
- Produces: `CalendarFeed(Long userId, String tokenHash, LocalDateTime createdAt)`, `void replaceToken(String tokenHash, LocalDateTime now)`; `Optional<CalendarFeed> CalendarFeedRepository.findByUserId(Long)`, `Optional<CalendarFeed> findByTokenHash(String)`, `void deleteByUserId(Long)`.

- [ ] **Step 1: Write the failing tests:** `findsFeedByTokenHash`; `unknownHashIsEmpty`; `sameHashTwiceIsRefused` and `secondRowForSameUserIsRefused` (`saveAndFlush` of the second → `DataIntegrityViolationException`).
- [ ] **Step 2: Run** `./gradlew test --tests CalendarFeedRepositoryTest` → FAIL.
- [ ] **Step 3: Implement.** Migration exactly as spec §5.1, with a comment on each constraint (why unique user, why unique hash). Entity: `@Getter`, `@NoArgsConstructor(access = PROTECTED)`, no setters (state changes through `replaceToken`), `createdAt` truncated to seconds like the other tables.
- [ ] **Step 4: Run** → PASS (reset `campus_test` first if Flyway complains: AGENTS.md §10).
- [ ] **Step 5: Commit** `feat(reservation): add calendar_feeds table`.

### Task 2: `CalendarTokenService`, settings endpoints (PR 1)

**Files:**
- Create: `backend/src/main/java/com/campus/reservation/service/CalendarTokenService.java`
- Create: `backend/src/main/java/com/campus/reservation/web/{CalendarFeedSettingsController,CalendarFeedStatusResponse,CalendarFeedCreatedResponse}.java`
- Test: `backend/src/test/java/com/campus/reservation/service/CalendarTokenServiceTest.java`, `backend/src/test/java/com/campus/reservation/web/CalendarFeedSettingsControllerTest.java`
- Modify: `docs/api-contract.md` (settings endpoints, "Calendar feed (Calin)"); personal doc `NN-calendar-link.md`

**Interfaces:**
- Consumes: Task 1; `CurrentUser.id()`; `UserApi.findActiveRole(Long)`; `AuditService.record(...)`; `Clock`.
- Produces:
  - `record CalendarFeedStatusResponse(boolean enabled)`, `record CalendarFeedCreatedResponse(String path)`
  - `@Service class CalendarTokenService { CalendarFeedStatusResponse status(); CalendarFeedCreatedResponse create(); void revoke(); Optional<Long> findActiveUserId(String token); static String hash(String token); }` (`findActiveUserId`: empty for malformed, unknown, replaced, turned-off, or a disabled/deleted user).
  - `GET | POST (201) | DELETE (204)` on `/api/v1/reservations/me/calendar-feed`.

- [ ] **Step 1: Write the failing service tests** (Mockito: `CalendarFeedRepository`, `UserApi`, `CurrentUser`, `AuditService`, fixed `Clock`):
  - `createReturnsAPathWithA43CharToken`: `path` matches `/api/v1/calendar/[A-Za-z0-9_-]{43}\.ics`.
  - `createSavesOnlyTheHash`: the saved `tokenHash` equals `hash(tokenFromPath)`, is 64 hex chars, and does not contain the token.
  - `secondCreateReplacesTheFirst`: after two creates, `findActiveUserId(firstToken)` is empty and `findActiveUserId(secondToken)` is the user (the mock repository answers by the hash currently stored).
  - `twoCreatesGiveDifferentTokens`.
  - `revokeDeletesTheRow`; `revokeWithoutALinkDoesNothing` (no audit).
  - `statusReflectsTheRow` (false, then true after create).
  - `findsActiveUserByToken`; `disabledUserIsEmpty` (`findActiveRole` empty); `unknownTokenIsEmpty`.
  - `malformedTokenNeverReachesTheDatabase`: for `null`, `""`, 42 chars, 44 chars, 43 chars with `/`, `+` or `.` → empty and `verifyNoInteractions(calendarFeedRepository)`.
  - `createWritesAudit` / `revokeWritesAudit`: action codes from Global Constraints, actor = the user, entity `USER` + the user's id.
- [ ] **Step 2: Run** `./gradlew test --tests CalendarTokenServiceTest` → FAIL.
- [ ] **Step 3: Implement.** `create()` and `revoke()` `@Transactional`; `status()` and `findActiveUserId` `@Transactional(readOnly = true)`. `Base64.getUrlEncoder().withoutPadding()`; `HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(...))`. Comments: why SHA-256 and not BCrypt (spec §2 row 3); why the pattern check comes first; why `findActiveRole` (Matei's #128: a disabled account loses access at once, the feed too).
- [ ] **Step 4: Write the failing controller tests:** `requiresLogin` (401 on all three); `statusIs200`; `createIs201WithPath`; `revokeIs204`. Run → FAIL. Implement the controller (`@Tag(name = "Calendar feed")`, `@Operation`, `@ResponseStatus`). Run → PASS.
- [ ] **Step 5:** `./gradlew spotlessApply build` → `BUILD SUCCESSFUL`. api-contract rows, personal doc (Part 1: a secret address like Google's own; Part 2: migration, token and hash, why shown once, tests). Commit `feat(reservation): add calendar link settings`, push `feat/reservation-calendar-token`, open the PR.

### Task 3: `IcsWriter` (needs nothing else)

**Files:**
- Create: `backend/src/main/java/com/campus/reservation/service/{IcsWriter,CalendarEvent}.java`
- Test: `backend/src/test/java/com/campus/reservation/service/IcsWriterTest.java`

**Interfaces:**
- Produces: `record CalendarEvent(String uid, Instant start, Instant end, String summary, String description)`; `final class IcsWriter { static String write(String calendarName, List<CalendarEvent> events, Instant now); static String escape(String text); static List<String> fold(String line); }` (pure, no Spring).

- [ ] **Step 1: Write the failing tests** (`NOV = LocalDateTime.of(2026, 11, 16, 10, 0)`, `JUL = LocalDateTime.of(2026, 7, 1, 10, 0)`, zone `Europe/Bucharest`; the conversion to `Instant` is done by the test the same way the service will do it):
  - `emptyCalendarIsValid`: starts with `BEGIN:VCALENDAR\r\nVERSION:2.0\r\n`, contains `PRODID:-//Campus Hub//Reservations//EN`, ends with `END:VCALENDAR\r\n`, no `VEVENT`.
  - `everyLineEndsWithCrlf`: removing all `\r\n` leaves no `\n` and no `\r`.
  - `writesOneEventPerReservation`: two events → two `BEGIN:VEVENT` / `END:VEVENT` pairs, in the given order.
  - `convertsWinterTimeToUtc`: `NOV` → `DTSTART:20261116T080000Z`.
  - `convertsSummerTimeToUtc`: `JUL` → `DTSTART:20260701T070000Z`.
  - `dtstampIsNowInUtc`.
  - `uidIsWrittenAsGiven`: `UID:reservation-123@campus-hub`.
  - `escapesSpecialCharacters`: `escape("a,b;c\\d\ne")` → `a\,b\;c\\d\ne` (backslash-n as two characters).
  - `foldsLinesLongerThan75Bytes`: a 200-character ASCII summary → every physical line ≤ 75 bytes, continuation lines start with one space, and unfolding (remove `\r\n `) gives the original line back.
  - `foldsWithoutSplittingAMultiByteLetter`: a summary of 100 × `ș` → every physical line decodes as valid UTF-8 (`new String(bytes, UTF_8)` round-trips) and is ≤ 75 bytes.
  - `shortLineIsNotFolded`.
- [ ] **Step 2: Run** `./gradlew test --tests IcsWriterTest` → FAIL.
- [ ] **Step 3: Implement.** Time format `DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)`. `fold` walks code points and counts UTF-8 bytes (first line 75, continuation lines 74 + the leading space). Comment at the top: the three format rules calendar apps are strict about (CRLF, escaping, folding) and that the tests pin each one.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(reservation): add iCalendar writer`.

### Task 4: `CalendarFeedService`, feed controller (PR 2; needs Matei's rule on `main`)

**Files:**
- Modify: `backend/src/main/java/com/campus/reservation/repository/ReservationRepository.java` (a user's reservations overlapping a range, any status, ordered by start)
- Create: `backend/src/main/java/com/campus/reservation/service/CalendarFeedService.java`, `backend/src/main/java/com/campus/reservation/web/CalendarFeedController.java`
- Modify: `docs/api-contract.md` ("Calendar feed (Calin)" section), `docs/modules/reservation.md`
- Test: `ReservationRepositoryTest` (new tests), `backend/src/test/java/com/campus/reservation/service/CalendarFeedServiceTest.java`, `backend/src/test/java/com/campus/reservation/web/CalendarFeedControllerTest.java`
- Create: personal doc `NN-calendar-feed.md`

**Interfaces:**
- Consumes: `CalendarTokenService.findActiveUserId` (Task 2); `IcsWriter.write`, `CalendarEvent` (Task 3); the new repository query; `CatalogApi.getAsset(Long)` → `AssetSummary(id, name, model, …)`; `Clock`.
- Produces: `@Service class CalendarFeedService { String feedFor(String token); }`; `GET /api/v1/calendar/{token}.ics` → `text/calendar;charset=UTF-8`.

- [ ] **Step 1: Repository query** (`@DataJpaTest`): a booking that only touches the range at either edge is included as an overlap or not according to the same rule as `existsOverlap` (state which in the test name); another user's bookings are left out; every status is returned; ordered by start. Run → FAIL, implement, run → PASS.
- [ ] **Step 2: Write the failing service tests** (Mockito; `Clock.fixed(2026-11-10T09:30:00Z, Europe/Bucharest)`; `ArgumentCaptor` for the range):
  - `unknownTokenIs404`: `NotFoundException` with code `CALENDAR_FEED_NOT_FOUND`; reservations never asked.
  - `asksThirtyDaysBackAndAhead`: the range passed to the query is campus-now − 30 d … + 30 d.
  - `hidesCancelledForceCancelledAndNoShow`; `keepsReturned`.
  - `usesTheCurrentAssetName`: `getAsset(7).name() == "Oscilloscope DS1054Z #2"` → `SUMMARY:Oscilloscope DS1054Z #2`.
  - `overdueGetsAPrefix`: `SUMMARY:OVERDUE: …`.
  - `asksTheCatalogOncePerAsset`: three bookings on asset 7 → `verify(catalogApi, times(1)).getAsset(7L)`.
  - `missingAssetStillListsTheBooking`: `getAsset` throws `NotFoundException` → summary `Equipment #7`.
  - `noBookingsGivesAnEmptyCalendar`.
- [ ] **Step 3: Run** → FAIL. **Implement** (`@Transactional(readOnly = true)`; comment at the status filter: an event that leaves the feed is removed by the calendar app; comment at `atZone(clock.getZone())`: bookings are campus time, the file is UTC). Run → PASS.
- [ ] **Step 4: Write the failing controller tests:** `feedNeedsNoLogin` (200 without a user, content type `text/calendar;charset=UTF-8`, header `Cache-Control: no-store`); `unknownTokenIs404WithCode`; `oddTokenIs404` (`abc`, 5,000 `a`s). Run → FAIL. **Implement:** `@GetMapping(value = "/{token}.ics", produces = "text/calendar;charset=UTF-8")` on a controller mapped to `/api/v1/calendar`, returning `ResponseEntity<String>`. Run → PASS.
- [ ] **Step 5:** `./gradlew spotlessApply build` → `BUILD SUCCESSFUL` (`SecurityPassIT`, `DemoFlowIT`, `OpenApiDocsIT`, ArchUnit included).
- [ ] **Step 6: Real app check:** create a link as `student1`, `curl -i` it without any header → 200 and the seeded booking; open the downloaded file in a calendar app; revoke → 404; report a HIGH incident on the booked asset as `student2` → the same (new) link shows the substitute's name.
- [ ] **Step 7:** api-contract section; `docs/modules/reservation.md`; personal doc (Part 2 includes spec §9's table and the `curl` output). Commit `feat(reservation): serve reservations as an iCalendar feed`, push `feat/reservation-calendar-feed`, open the PR.

### Task 5: Calendar sync page (PR 3)

**Files:**
- Create: `frontend/src/api/calendarFeed.ts`, `frontend/src/pages/CalendarSyncPage.tsx`
- Modify (Stefania reviews): `frontend/src/App.tsx` (route `calendar-sync`), `frontend/src/layout/navigation.ts` (entry `{ label: 'Calendar sync', path: '/calendar-sync', icon: IconCalendarShare }` under "Mine")
- Create: personal doc `NN-calendar-sync-page.md`, screenshots `docs/screenshots/calendar-0*.png`

**Interfaces:**
- Consumes: the three settings endpoints (Task 2), the feed path (Task 4).
- Produces: `fetchCalendarFeed(): Promise<{ enabled: boolean }>`, `createCalendarFeed(): Promise<{ path: string }>`, `revokeCalendarFeed(): Promise<void>`.

- [ ] **Step 1:** `calendarFeed.ts` with JSDoc naming each endpoint.
- [ ] **Step 2:** The page's three states exactly as spec §8 (texts copied from there). The address field is built from `window.location.origin + path`; Mantine `CopyButton`; "Make a new link" and "Turn off" behind a confirm `Modal`; the freshly created address lives only in component state (a reload shows "A calendar link is active").
- [ ] **Step 3:** Route + menu entry.
- [ ] **Step 4: Manual check:** create → copy → paste in a new tab downloads/shows the calendar text; reload → no address shown; new link → the old address answers 404; turn off → 404.
- [ ] **Step 5:** `npm run lint && npm run build`; screenshots; personal doc; commit `feat(frontend): add calendar sync page`; push `feat/frontend-calendar-sync`; PR, ask Stefania for the review.
- [ ] **Step 6 (after Matei's W7 deploy):** subscribe to the AWS link from one real Google Calendar account; add the screenshot and the observed refresh delay to the personal doc.

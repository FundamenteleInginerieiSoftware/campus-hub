# Live notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the app notifies a user (for example after the reassignment engine moved their booking), that user's open browser shows the message within about a second.

**Owner:** Liviu (split of 10 Nov: each new feature goes to the member who owns its data; proposed until the team agrees). This plan is a guide: he writes the code.

**Architecture:** Liviu's `notification` module keeps one open Server-Sent-Events response per browser tab (`LiveStreams`, in memory) behind `GET /api/v1/live/stream`, protected by the normal JWT filter. Each `NotificationService` adapter calls `LiveStreams.send` for the notified user: Liviu's `LoggingNotificationService` (dev/test) and, in Matei's small PR, `SesNotificationService` (prod). No Spring event, no new module. The frontend opens the stream with `fetch`, parses events and shows them as cards.

**Tech Stack:** Spring Boot 4.1.1 (Spring MVC `SseEmitter`, `@Scheduled`, `@Async`, `@EventListener`), Java 25, JUnit 5 + AssertJ + Mockito, MockMvc async; React 19 + Mantine 9 + TypeScript (`fetch` streaming, no new library).

**Spec:** `docs/superpowers/specs/2026-11-10-live-notifications-design.md`

## Global Constraints

- Never edit a teammate's file (AGENTS.md §3): not `SesNotificationService` (Matei), not `SecurityConfig` (Matei), not `AppLayout.tsx` or `package.json` (Stefania), not the ArchUnit tests (Calin).
- The new classes import from other modules only `com.campus.common.*` (`CurrentUser`, `Clock`, errors). Packages `notification.service`, `notification.web`.
- Constants exactly: `STREAM_LIFETIME = Duration.ofMinutes(10)`, `HEARTBEAT_EVERY = 20 s`, `MAX_STREAMS_PER_USER = 5`.
- Endpoint `GET /api/v1/live/stream`, `text/event-stream`, header `Cache-Control: no-store`. SSE event name `notification`; heartbeat = SSE comment `hb`. Error `ConflictException("LIVE_STREAM_LIMIT", "Too many open tabs for this account")`.
- `record LiveEvent(String type, String title, String message, LocalDateTime at)`; `type` is the literal `"NOTIFICATION"`.
- Client retry delays in ms: `1000, 2000, 5000, 15000`, then `30000` forever; reset after a stream that delivered data; no retry on 401 or 409. Cards: max 3 visible, 8 s each.
- No `SecurityConfig` change unless Task 2's check fails (then ask Matei for exactly the one line named there).
- Tests: one rule per test, `.as("<rule>; see <Class.method>")`. Comments only for hidden behaviour, *why*, links to teammates' code. Commits with your own git identity, **no AI trailer**. Each PR ships its personal doc + `02-files-explained.md` + docs `README.md` rows.
- Before each push: `cd backend && ./gradlew spotlessApply build` → `BUILD SUCCESSFUL`; frontend `npm run lint && npm run build`.

## Review Focus

1. **A tab is closed without telling the server** → the next write to it fails; that stream must disappear and every other stream must still get the message. Test: `LiveStreamsTest.failingStreamIsDroppedAndOthersStillReceive` (Task 1).
2. **Heartbeat and a notification written at the same instant** → two threads on one `SseEmitter` corrupt the stream. Test: `LiveStreamsTest.concurrentWritesAreNotInterleaved` (Task 1).
3. **Six tabs of the app open** → the 6th gets no stream and must not retry in a loop; the first five keep working. Tests: `LiveStreamsTest.sixthStreamIsRefused` (Task 1); manual check in Task 4, Step 5.
4. **An SSE event split across two network chunks** (`data: {"ty` + `pe":…}\n\n`) → the client must wait for the blank line, not parse half a JSON. Manual check with a throttled connection in Task 4, Step 5; the parser keeps a buffer (Task 3, Step 2).
5. **The stream ends after 10 minutes or the laptop wakes from sleep** → the client reconnects by itself; a logged-out user (401) does not keep retrying. Manual check in Task 4, Step 5 (temporarily set the lifetime to 20 s locally, do not commit it).

---

### Task 1: `LiveStreams` (who is connected)

**Files:**
- Create: `backend/src/main/java/com/campus/notification/service/LiveStreams.java`, `backend/src/main/java/com/campus/notification/web/LiveEvent.java`
- Modify: `backend/src/main/java/com/campus/notification/package-info.java` (now also: the live browser stream)
- Test: `backend/src/test/java/com/campus/notification/service/LiveStreamsTest.java`

**Interfaces:**
- Produces: `record LiveEvent(String type, String title, String message, LocalDateTime at) { static LiveEvent notification(String title, String message, LocalDateTime at); }`; `@Component class LiveStreams { SseEmitter open(Long userId); @Async void send(Long userId, LiveEvent event); @Scheduled(fixedRate = 20_000) void heartbeat(); int openCount(Long userId); }`. For tests, a package-private `SseEmitter register(Long userId, SseEmitter emitter)` that `open` delegates to.

- [ ] **Step 1: Write the failing tests** (plain JUnit; a `RecordingEmitter extends SseEmitter` that overrides `send(SseEventBuilder)` to store what was sent, and a `BrokenEmitter` whose `send` throws `IOException`):
  - `onlyTheAddressedUserReceives`: streams for users 1 and 2, `send(1, e)` → user 1's emitter recorded 1 event, user 2's 0.
  - `everyTabOfTheUserReceives`: two streams for user 1 → both recorded.
  - `failingStreamIsDroppedAndOthersStillReceive`: user 1 has a broken and a recording emitter → no exception, the recording one got the event, `openCount(1) == 1`.
  - `sixthStreamIsRefused`: five `open(1)` succeed; the sixth throws `ConflictException` with code `LIVE_STREAM_LIMIT`; `openCount(1) == 5`.
  - `limitIsPerUser`: five streams for user 1 do not block `open(2)`.
  - `completedStreamFreesItsSlot`: after `emitter.complete()` on one of five (callbacks run through `register`), `openCount(1) == 4` and a new `open(1)` succeeds.
  - `heartbeatReachesEveryStream`: three emitters of two users → each recorded one comment event.
  - `sendWithoutAnyStreamDoesNothing`: `send(99, e)` → no exception.
  - `concurrentWritesAreNotInterleaved`: an emitter whose `send` records "enter", sleeps 20 ms, records "exit"; one thread calls `heartbeat()` while another calls `send(1, e)` → the log is enter, exit, enter, exit (never enter, enter).
- [ ] **Step 2: Run** `./gradlew test --tests LiveStreamsTest` → FAIL.
- [ ] **Step 3: Implement.** `ConcurrentHashMap<Long, List<SseEmitter>>` with `CopyOnWriteArrayList` values; `new SseEmitter(STREAM_LIFETIME.toMillis())`; `onCompletion`, `onTimeout`, `onError` all remove the emitter; every write inside `synchronized (emitter)`; events built with `SseEmitter.event().name("notification").data(event, MediaType.APPLICATION_JSON)` and `SseEmitter.event().comment("hb")`. The count check and the add happen under one lock per user (two tabs opening at once must not both become "the fifth"). Comments: why the heartbeat exists (CloudFront's 30 s idle timeout, closed tabs), why the 6th is refused instead of closing the oldest (spec §2 row 9), why in memory is enough (one server, ADR-002), why `send` is `@Async` (a slow browser must never hold up Calin's engine; it works because the adapters call it through Spring's proxy).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(notification): add in-memory live stream registry`.

### Task 2: `LiveStreamController`, contract, PR 1

**Files:**
- Create: `backend/src/main/java/com/campus/notification/web/LiveStreamController.java`
- Test: `backend/src/test/java/com/campus/notification/web/LiveStreamControllerTest.java`, `backend/src/test/java/com/campus/notification/LiveStreamIT.java`
- Modify: `docs/api-contract.md` ("Live updates (Liviu)")
- Possibly ask Matei for: one line in `backend/src/main/java/com/campus/config/SecurityConfig.java` (only if Step 4 fails as described)
- Create: `documentation/Nedelcu_Liviu/NN-live-stream.md` + 02 + README rows

**Interfaces:**
- Consumes: `LiveStreams.open(Long)` (Task 1); `CurrentUser.id()`.
- Produces: `GET /api/v1/live/stream` → `SseEmitter` (`produces = MediaType.TEXT_EVENT_STREAM_VALUE`).

- [ ] **Step 1: Write the failing slice tests** (`@WebMvcTest(LiveStreamController.class)` + `@Import(SecurityConfig.class)`, `@MockitoBean LiveStreams`, `@MockitoBean CurrentUser`): `requiresLogin` (401, `$.code == "UNAUTHORIZED"`); `opensAStreamForTheLoggedInUser` (`request().asyncStarted()`, `verify(liveStreams).open(12L)`); `limitIs409` (`open` throws → `$.code == "LIVE_STREAM_LIMIT"`).
- [ ] **Step 2: Run** → FAIL. **Implement** the controller (sets `Cache-Control: no-store` on the response; `@Tag(name = "Live updates")`, `@Operation` explaining that the response stays open). Run → PASS.
- [ ] **Step 3: Write `LiveStreamIT`** (`@SpringBootTest @AutoConfigureMockMvc`, real login as `student1@campus.test` like `DemoFlowIT`): open the stream (`MvcResult` with async started); call `liveStreams.send(student1Id, LiveEvent.notification("T1", "for one", NOW))` and `send(student2Id, …"for two"…)`; assert the response content so far contains `event:notification` and `for one` and does **not** contain `for two`; then complete the emitter and `asyncDispatch` the result.
- [ ] **Step 4: Run** `./gradlew test --tests LiveStreamIT`. Expected PASS with no `AccessDeniedException` in the log. If the async dispatch is rejected (401/403 or "response is already committed" with an access-denied cause): ask Matei to add `.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()` as the **first** rule in `SecurityConfig.authorizeHttpRequests`, with a bug-fix comment (what failed: Spring re-checks the rules when an async response finishes, and that second pass carries no token; why it is safe: an ASYNC dispatch can only continue a request that already passed the rules). Once his line is on `main`, rebase and run again → PASS, and `./gradlew test --tests SecurityConfigTest --tests SecurityPassIT` → PASS.
- [ ] **Step 5:** `./gradlew spotlessApply build` → `BUILD SUCCESSFUL` (ArchUnit: if a rule fails on `notification`, stop and tell Calin).
- [ ] **Step 6: Real app check:** `curl -N -H "Authorization: Bearer <token>" localhost:8080/api/v1/live/stream` stays open and prints `:hb` every 20 s; without the header → 401 JSON; six parallel curls → the sixth gets 409.
- [ ] **Step 7:** api-contract section, personal doc (Part 1: "a phone call the server keeps open" vs. "asking every few seconds"; Part 2: files, the three constants and why, the security answer of spec §2 row 2, tests, curl output). Commit `feat(notification): add live update stream endpoint`, push `feat/notification-live-stream`, open the PR.

### Task 3: `live.ts` (browser side of the stream)

**Files:**
- Create: `frontend/src/api/live.ts`

**Interfaces:**
- Consumes: `tokenStorage.get()` from `client.ts`; `GET /api/v1/live/stream` (Task 2).
- Produces: `type LiveEvent = { type: 'NOTIFICATION'; title: string; message: string; at: string }`; `connectLive(): () => void`; `subscribeLive(listener: (event: LiveEvent) => void): () => void`; exported for reuse and reading: `parseSseChunk(buffer: string): { events: LiveEvent[]; rest: string }`.

- [ ] **Step 1:** `parseSseChunk`: split `buffer` on `\n\n` (after replacing `\r\n` with `\n`); the last piece (no blank line after it yet) is returned as `rest`; in each complete block, lines starting with `:` are ignored, lines starting with `data:` are joined and `JSON.parse`d inside try/catch (a block that is not valid JSON is skipped, not thrown).
- [ ] **Step 2:** `connectLive`: one `AbortController`; loop: `fetch('/api/v1/live/stream', { headers: { Authorization, Accept: 'text/event-stream' }, signal })`; status 401 or 409 → stop for good; other non-200 or a network error → wait the next delay, retry; on 200 read `response.body.getReader()` with a `TextDecoder('utf-8', …)` in streaming mode, append to the buffer, run `parseSseChunk`, call the listeners; when the reader reports done → reset the delay if any byte arrived, then reconnect. The returned function aborts and stops the loop. No token → do nothing.
- [ ] **Step 3:** `subscribeLive` keeps a `Set` of listeners; a listener that throws must not stop the others (try/catch per listener).
- [ ] **Step 4:** `npm run lint && npm run build` → clean. Commit `feat(frontend): add live stream client`.

### Task 4: `LiveToasts.tsx` and PR 2

**Files:**
- Create: `frontend/src/live/LiveToasts.tsx`
- Create: `documentation/Nedelcu_Liviu/NN-live-toasts.md`, screenshots `docs/screenshots/live-0*.png`
- (Stefania, her PR or her review): one import + `<LiveToasts />` in `frontend/src/layout/AppLayout.tsx`

**Interfaces:**
- Consumes: `connectLive`, `subscribeLive`, `LiveEvent` (Task 3).
- Produces: `export default function LiveToasts()` with no props.

- [ ] **Step 1:** Component: `useEffect` → `const stop = connectLive(); const off = subscribeLive(add); return () => { off(); stop() }`. State: the last events with a local id; `add` keeps the newest 3; a timer per card removes it after 8000 ms (cleared on unmount). Render: nothing when empty; otherwise a fixed box bottom-right (`position: fixed`, 16 px from the edges, above page content) with Mantine `Notification` cards (`title`, `message`, `onClose`), wrapper `aria-live="polite"`.
- [ ] **Step 2:** For the local check only, mount it in your working copy of `AppLayout.tsx` (do **not** commit that line unless Stefania agreed to take it in this PR).
- [ ] **Step 3: Manual check, part 1** (backend + `npm run dev`; the bridge does not exist yet, so trigger a message from a throwaway `@Profile("dev")` test endpoint kept **uncommitted**, or from `LiveStreamIT`-style code in a scratch test): a message appears within 1 s; a fourth message pushes the oldest out; a card closes after 8 s and on ✕; dark theme readable.
- [ ] **Step 4: Manual check, part 2 (Review Focus 3–5):** six tabs → the sixth shows no errors in the console loop (one 409, then silence), the other five still receive; DevTools throttling "Slow 4G" → messages still parse; lifetime set to 20 s locally → the Network tab shows a new stream request about 1 s after each one ends; log out in another tab (token removed) → after the next reconnect a single 401 and no more requests.
- [ ] **Step 5:** `npm run lint && npm run build`; screenshots; personal doc; commit `feat(frontend): add live notification cards`; push `feat/frontend-live-toasts`; open the PR; ask Stefania to mount it (or to OK the line).

### Task 5: Push from `LoggingNotificationService` (PR 3)

**Files:**
- Modify: `backend/src/main/java/com/campus/notification/service/LoggingNotificationService.java` (call `liveStreams.send` after the log line)
- Modify: `backend/src/test/java/com/campus/notification/LiveStreamIT.java` (end-to-end test)
- Test: `LoggingNotificationServiceTest` (new or existing class)
- Create: `documentation/Nedelcu_Liviu/NN-live-notifications.md` + 02 + README rows
- **Not here (Matei's file):** the same call in `SesNotificationService` for prod. Matei opens his own small PR once PR 1 is on `main`.

**Interfaces:**
- Consumes: `LiveStreams.send`, `LiveEvent.notification` (Task 1); `Clock`; `NotificationService.notifyUser(Long, String, String)`.
- Produces: nothing new; `notifyUser` now also pushes.

- [ ] **Step 1: Write the failing tests:**
  - `LoggingNotificationServiceTest.pushesToTheNotifiedUser`: `notifyUser(12L, "Reservation Reassigned", body)` → `verify(liveStreams).send(eq(12L), argThat(e -> e.type().equals("NOTIFICATION") && e.title().equals("Reservation Reassigned") && e.message().equals(body) && e.at().equals(NOW)))` (fixed `Clock`).
  - `LiveStreamIT.notifyUserReachesTheOpenStream`: open the stream as student 1, call the real `NotificationService.notifyUser(student1Id, "Subject A", "Body A")` → the stream content contains `Subject A`; a call for student 2 does not appear.
- [ ] **Step 2: Run** → FAIL. **Implement** (constructor-inject `LiveStreams` and `Clock`; comment: every notification of the app goes through here in dev, so one call covers the engine, the jobs and transfers). Run → PASS.
- [ ] **Step 3:** `./gradlew spotlessApply build` → `BUILD SUCCESSFUL`.
- [ ] **Step 4: The demo, for real** (two browsers, `<LiveToasts />` mounted): student 1 books an oscilloscope for tomorrow; student 2 reports a HIGH incident on that unit → the card "Reservation Reassigned" appears in student 1's browser with no reload. Record it (screenshots or a short screen capture) for the personal doc.
- [ ] **Step 5:** Personal doc (Part 1: the demo in four numbered steps; Part 2: the chain incident → engine → notification → stream → card with the owner of each link, limits from spec §8, tests). Commit `feat(notification): push notifications to open browsers`, push `feat/notification-live-push`, open the PR.
- [ ] **Step 6 (after the W7 deploy):** repeat Step 4 through CloudFront; leave a tab idle for 2 minutes and check it still receives. If messages arrive late or in bursts, look at CloudFront compression/buffering for `/api/v1/live/*` and write the finding in the deploy doc.

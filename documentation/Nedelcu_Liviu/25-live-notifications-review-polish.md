# 25 — Live Notifications Review Follow-ups (Backend & Frontend Polish)

**Issue:** Refs [#290](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/290) (follow-up nits from reviews on #339 and #340)  
**Date:** 21 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

After the three main Live Notifications PRs (`#317`, `#339`, `#340`) were merged into `main`, Stefania and Matei left a few small, non-blocking suggestions to make the feature even cleaner and safer:

1. **Toast stack accessibility & React purity (`LiveToasts.tsx`):** Screen readers only announce new notifications if the `aria-live="polite"` box is already on the page before the first message arrives, so the empty container now stays mounted (with `pointer-events: none` so it never blocks clicks). Evicted toast timers are now cleared outside the React `setItems` updater so state updates stay pure.
2. **Closing the disable-vs-open micro-race (`LiveStreamController.java`):** If an admin disables a student's account in the exact microsecond between the JWT filter check and `liveStreams.open()`, re-checking `userApi.findActiveRole(userId)` right after opening closes the stream immediately and returns `401`.
3. **Stronger tests (`LiveStreamIT.java`, `LiveStreamControllerTest.java`, `LoggingNotificationServiceTest.java`):** Added a real end-to-end integration test that opens a stream, calls `POST /api/v1/admin/users/{id}/disable`, verifies all open streams close (`openCount == 0`), and confirms reconnecting gets `401`. Also upgraded `LoggingNotificationServiceTest` to assert each field with explicit rule messages.

---

## Part 2: Technical Details

### 1. Frontend (`frontend/src/live/LiveToasts.tsx`)
- Always renders `<div aria-live="polite" ...>` (with `pointerEvents: items.length === 0 ? 'none' : 'auto'`) and renders `<Stack>` inside only when `items.length > 0`.
- Tracks visible toast IDs in `activeIdsRef` so evicting timers when more than `MAX_VISIBLE_TOASTS` (3) arrive happens outside `setItems(...)`, keeping the updater pure under React `StrictMode`.
- Updated the JSDoc/comment to clarify `<LiveToasts />` is to be mounted once inside the authenticated `AppLayout`.
- Updated `23-live-toasts-frontend.md` with a note on `401` behavior (stops the SSE loop quietly; `client.ts` handles token cleanup + redirect on the next API call).

### 2. Backend (`com.campus.notification`)
- **`LiveStreamController.java`:** Injects `UserApi` and re-checks `userApi.findActiveRole(userId)` right after `liveStreams.open(userId)`; if empty, calls `liveStreams.completeAll(userId)` and throws `UnauthorizedException("Account is disabled")` (`401 UNAUTHORIZED`).
- **`LiveStreamControllerTest.java`:** Mocks `UserApi` and adds `rejectsStreamWhenUserWasDisabledMidRequest()`.
- **`LiveStreamIT.java`:** Adds `disablingUserClosesOpenStreamsAndBlocksReconnect()` testing the real end-to-end `POST /api/v1/admin/users/{id}/disable` → `UserDisabledEvent` → `UserDisabledLiveStreamListener` → `openCount == 0` → reconnect `401`.
- **`LoggingNotificationServiceTest.java`:** Replaced bare `argThat(...)` with `ArgumentCaptor<LiveEvent>` and AssertJ `.as("...; see LoggingNotificationService.notifyUser")` messages.

### 3. Verification
- Backend: `./gradlew spotlessApply build` (includes ArchUnit module-boundary tests, `LiveStreamControllerTest`, `LoggingNotificationServiceTest`, and `LiveStreamIT`).
- Frontend: `npm run lint && npm run build`.

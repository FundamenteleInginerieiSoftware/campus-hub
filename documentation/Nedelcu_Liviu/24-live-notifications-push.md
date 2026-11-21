# 24 — Live Notification Push from `LoggingNotificationService` & Disabled-User Stream Close

**Issue:** Refs [#290](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/290) (PR 3 of 3 for Live notifications)  
**Date:** 21 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

This final backend piece wires the dev/test notification adapter into the live SSE stream so the complete live demo works locally just as it does on AWS (`SesNotificationService`, #332):

1. Student 1 books an oscilloscope for tomorrow and stays on the app.
2. Student 2 reports a `HIGH` severity incident on that oscilloscope.
3. Calin's reassignment engine moves Student 1's booking to a substitute oscilloscope and calls `NotificationService.notifyUser(...)`.
4. `LoggingNotificationService` (dev) or `SesNotificationService` (prod) pushes the message to `LiveStreams`, and Student 1's browser displays the toast card within a second without reloading.

Additionally, if an administrator disables a user account (`UserDisabledEvent`, #302), any open SSE streams for that account are closed immediately once the transaction commits, closing the previous up-to-10-minute window.

---

## Part 2: Technical Details

### 1. End-to-End Chain & Ownership
| Step | Component | Owner |
|---|---|---|
| Incident reported | `IncidentService` → `AssetDamagedEvent` | Stefania |
| Auto-reassignment | `ReassignmentService` → `NotificationService.notifyUser(...)` | Calin |
| Notification push | `LoggingNotificationService` (dev, Liviu) / `SesNotificationService` (prod, Matei) → `LiveStreams.send(...)` | Liviu / Matei |
| SSE delivery | `LiveStreams` + `LiveStreamController` (`GET /api/v1/live/stream`) | Liviu |
| Browser toast | `frontend/src/api/live.ts` + `frontend/src/live/LiveToasts.tsx` (mounted in `AppLayout.tsx` by Stefania) | Liviu / Stefania |

### 2. Files Changed / Added
- [`LoggingNotificationService.java`](../../backend/src/main/java/com/campus/notification/service/LoggingNotificationService.java): Constructor-injects `LiveStreams` and `Clock`; calls `liveStreams.send(userId, LiveEvent.notification(subject, body, now))` after logging.
- [`UserDisabledLiveStreamListener.java`](../../backend/src/main/java/com/campus/notification/service/UserDisabledLiveStreamListener.java): `@TransactionalEventListener(phase = AFTER_COMMIT)` on `UserDisabledEvent` calling `liveStreams.completeAll(event.userId())`.
- [`LoggingNotificationServiceTest.java`](../../backend/src/test/java/com/campus/notification/service/LoggingNotificationServiceTest.java): Verifies `notifyUser` pushes `LiveEvent` with exact `type`, `title`, `message`, and `Clock`-based `at`.
- [`UserDisabledLiveStreamListenerTest.java`](../../backend/src/test/java/com/campus/notification/service/UserDisabledLiveStreamListenerTest.java): Verifies `completeAll` invocation and `@TransactionalEventListener(AFTER_COMMIT)`.
- [`LiveStreamIT.java`](../../backend/src/test/java/com/campus/notification/LiveStreamIT.java): Added `notifyUserReachesTheOpenStream()` verifying the real `NotificationService` pushes only to the recipient's open stream.

### 3. Accepted Limits (Spec §8)
- Messages sent while a user has no open tab are not replayed (the user still receives the e-mail in prod and sees the updated status in "My reservations").
- In-memory registry assumes a single app server instance (ADR-002).

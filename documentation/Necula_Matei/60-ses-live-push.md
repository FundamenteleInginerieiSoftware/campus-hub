# SES notifications also pushed live to open tabs (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-20
**Branch / PR:** `feat/notification-ses-live-push` (#NNN)
**See also:** `23-s3-ses-adapters.md` (the SES adapter), `06-async-scheduling.md`, issue #290 and Liviu's live stream (#317)

---

## Part 1: In simple terms

1. **Before:** on AWS, when the system tells a student "your booking was moved to another camera", the only thing that happened was an e-mail. The student had to open their inbox to find out.
2. **Liviu's part (#317):** he built a "live channel" from the server to the open browser tab, so a message can pop up on the screen without reloading the page.
3. **What I built:** on AWS, every notification now does two things: first it goes to the live channel (the open tab shows it at once), then the e-mail is sent as before.
4. **Why the screen message goes first:** sending an e-mail through Amazon can be slow (up to 20 seconds before we give up) and can fail (for example the user was deleted meanwhile). The screen message must not wait for that, and must not be lost because of it.
5. **Result:** an open tab shows the news in a moment; the e-mail still arrives; if the e-mail fails, the tab still got the message. If nobody has the site open, the live part simply does nothing.
6. **Not part of this PR:** on a laptop (dev), Liviu's `LoggingNotificationService` gets its own push in his next PR (#290, PR 3). This one is for AWS only.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/src/main/java/com/campus/notification/service/SesNotificationService.java` | Constructor gets `LiveStreams` + `Clock`; `notifyUser` first calls `liveStreams.send(userId, LiveEvent.notification(subject, body, LocalDateTime.now(clock)))`, then does the SES e-mail as before |
| `backend/src/test/java/com/campus/notification/service/SesNotificationServiceTest.java` | 3 new tests (7 in total): push before mail, failed mail still pushes, unknown user still pushes and sends no e-mail |
| `documentation/Necula_Matei/60-ses-live-push.md`, `02-files-explained.md`, `README.md` | This doc and its index rows |
| `docs/whats-left.md`, `AGENTS.md` §7 | Matei's lines only |

No API change: `GET /api/v1/live/stream` is already in `docs/api-contract.md` (Liviu, #317). Nothing to edit there.

### Why this order

```java
// first line inside the try of notifyUser
liveStreams.send(userId, LiveEvent.notification(subject, body, LocalDateTime.now(clock)));
// then: userApi.getById(userId) -> SES sendEmail (failures are caught and logged)
```

| Reason | Detail |
|---|---|
| SES is slow | `AwsConfig` allows up to 20 s before the SES call times out. The tab must not wait for it. |
| The lookup can fail | `userApi.getById` throws for a deleted user (#148). The push only needs the user id, so it goes out first. |
| Cheap and safe | `LiveStreams.send` is `@Async` itself and does nothing when the user has no open tab, so it adds no delay and no error to the caller. |
| Time | The shared `Clock` (Calin, `common/time`) gives campus local time without offset, like the rest of the API. Not `LocalDateTime.now()` (AGENTS §3). |

### How it connects

| Teammate / module | Link |
|---|---|
| Liviu, `notification` (`LiveStreams`, `LiveEvent`, `LiveStreamController`) | The SSE stream from #317 (`GET /api/v1/live/stream`, event name `notification`). I only call `send`. His frontend (`live.ts`, `LiveToasts.tsx`, PR 2) shows the toast; his PR 3 does the same push in `LoggingNotificationService` for dev. |
| Calin, reassignment engine and reservation jobs | They call `NotificationService.notifyUser`; their code does not change. In prod that bean is `SesNotificationService`, so their notifications now reach open tabs too. |
| Calin, `common/time` | The `Clock` bean. |
| Matei, `AwsConfig` | The 20 s SES timeout that motivates the order. |

### How tested

| Test | What it proves |
|---|---|
| `pushesTheNotificationLiveBeforeTheMail` | Mockito `InOrder`: `liveStreams.send` runs before `ses.sendEmail`, with the time from a fixed `Clock` |
| `aFailedMailStillPushesLive` | an `SesV2Exception` is still swallowed, and the push already happened |
| `anUnknownUserStillGetsTheLivePush` | `getById` throws `NotFoundException`: push sent, no SES call |
| the 4 older tests | e-mail content, failures not propagating, `@Async` still present |

Result: all 7 tests in `SesNotificationServiceTest` green, and `ProdProfileStartupTest` (prod profile, context starts with the new constructor) green.

### How to check on AWS (after the next deploy)

1. Open the CloudFront URL in **two tabs**, logged in as the same student.
2. As admin, retire or damage an asset that student has a future booking on, so Calin's engine moves the booking.
3. Both tabs of the student get the `notification` event (toast), and the e-mail still arrives in the inbox (SES sandbox: the address must be verified).
4. Optional: close both tabs and repeat; only the e-mail arrives, no error in the log.

### Problems and fixes

| Problem | Fix |
|---|---|
| Where to put the push so a slow or failing SES can't affect it | First call in `notifyUser`, before the lookup and the e-mail (tests pin the order) |
| Unknown user would have lost the live update | The push does not need the lookup; test `anUnknownUserStillGetsTheLivePush` |

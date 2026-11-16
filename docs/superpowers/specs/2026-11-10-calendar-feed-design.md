# Calendar feed (my bookings in Google / Apple / Outlook calendar): design

**Status:** proposed, 2026-11-10 (written by Matei; Calin confirms at the next meeting). **Owner:** Calin (inside his `reservation` module). **Needs from teammates:** one `SecurityConfig` rule from Matei.

**Split of the four new features (10 Nov):** each goes to the member who owns the data it reads: pickup pass → Matei, calendar feed → Calin (the feed is his bookings, statuses and reassignments), live notifications → Liviu, equipment reliability → Stefania. Proposed until the team agrees.
**Deadline:** before the feature freeze (Mon 7 Dec, W10). Must not slow down M1 (15 Nov) or the W7 deploy.

## 1. Goal

A student clicks **Create calendar link** once and pastes the link into their calendar app ("subscribe by URL"). From then on their lab bookings appear next to their classes, and the calendar app keeps them up to date by itself:

- a new booking appears;
- a cancelled booking disappears;
- a booking that the reassignment engine **moved to another unit** shows the new unit's name, without the student opening our app.

Success = in the demo the link, opened in a browser or imported in a calendar app, shows the student's bookings; after an incident moves one of them, the same link shows the substitute; after "Turn off", the link answers 404.

## 2. Decisions (proposed, Calin confirms)

| # | Decision | Why |
|---|---|---|
| 1 | The feed is the standard iCalendar text format (`.ics`, RFC 5545), written by our own small class | Every calendar app reads it. ~60 lines we can explain; no library |
| 2 | The link carries a **random secret token** (32 random bytes) instead of a login | Calendar apps can't log in or send our JWT. This is how Google's own "secret address" works |
| 3 | We store only the **SHA-256 hash** of the token, in a new table `calendar_feeds` (one row per user) | A leaked database can't be turned into working links. SHA-256 (not BCrypt) because the token is 256 random bits: it can't be guessed, and a plain hash lets us find the user with one indexed lookup |
| 4 | The link is shown **once**, when created. "Make a new link" replaces it; "Turn off" removes it | Follows from row 3 (we can't show what we don't store). Also gives the user a way to cut off a leaked link |
| 5 | The feed endpoint is public (`permitAll`) and the token is the only key | One `SecurityConfig` rule, which Matei adds (his file). Everything else stays behind the JWT |
| 6 | Times are written in **UTC** (`…Z`), converted from campus time | Avoids the long time-zone block of the format; every app shows them in the viewer's zone |
| 7 | Window: 30 days back, 30 days ahead. Shown: `CONFIRMED`, `CHECKED_OUT`, `OVERDUE`, `RETURNED`. Hidden: `CANCELLED`, `FORCE_CANCELLED`, `NO_SHOW` | An event that leaves the feed is removed by the calendar app, which is what a cancellation should look like |
| 8 | Everything lives inside the `reservation` module, including the token table | The data is Calin's bookings. `reservation` already depends on `user.api` (account still enabled?) and `catalog.api` (asset names), so: no new module, no new facade method, no change to Matei's `users` table, no cycle |

## 3. Ownership: what we build vs. what we ask for

| Who | What |
|---|---|
| Calin | In `reservation/`: migration `calendar_feeds`, entity `CalendarFeed`, `CalendarFeedRepository`, one query on his `ReservationRepository` (a user's reservations overlapping a range), `CalendarTokenService`, `IcsWriter`, `CalendarFeedService`, `CalendarFeedSettingsController`, `CalendarFeedController`; `frontend/src/api/calendarFeed.ts`, `CalendarSyncPage.tsx`; api-contract new section; `docs/modules/reservation.md`; personal docs |
| Matei | One `SecurityConfig` rule (`GET /api/v1/calendar/*` → `permitAll`) + the bearer-token resolver skip, with `SecurityConfigTest` tests and the api-contract §2 row, in his own small PR. Can land before Calin's endpoint exists |
| Stefania | OK for the route `calendar-sync` + menu entry "Calendar sync" under "Mine" |
| Liviu | Nothing. (Later, optional: the lab room **name** on `AssetSummary` would let the event show a location) |

## 4. Architecture

```
com.campus.reservation               (Calin, existing module)
  domain/      CalendarFeed               userId, tokenHash, createdAt; replaceToken(hash)
  repository/  CalendarFeedRepository     findByUserId, findByTokenHash, deleteByUserId
               ReservationRepository      + a user's reservations overlapping a range, any status
  service/     CalendarTokenService       create / revoke / status for the logged-in user; token -> active user id
               IcsWriter                  pure: list of events -> .ics text
               CalendarFeedService        token -> user -> reservations -> events
  web/         CalendarFeedSettingsController   /api/v1/reservations/me/calendar-feed
               CalendarFeedController           GET /api/v1/calendar/{token}.ics

com.campus.config                    (Matei)
               SecurityConfig             + permitAll for GET /api/v1/calendar/*
```

- **Dependencies:** none new. `reservation → user.api` (`UserApi.findActiveRole`: empty for a disabled or deleted account) and `reservation → catalog.api` (`CatalogApi.getAsset`: the current asset name) already exist. No AGENTS.md or ArchUnit change.
- "Now" and the campus zone come from the shared `Clock` (`clock.getZone()` is Europe/Bucharest).

## 5. Data

### 5.1 Migration (`V<timestamp>__reservation_create_calendar_feeds.sql`, new file, Calin's)

```sql
CREATE TABLE calendar_feeds (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id    BIGINT      NOT NULL UNIQUE REFERENCES users (id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP   NOT NULL
);
```

No row = no link. `user_id` unique: one link per user (a new link replaces the hash in the same row). `token_hash` unique: the lookup by hash uses its index. Matei's `users` table is not touched.

### 5.2 Token

- Create: 32 bytes from `SecureRandom` → base64url without padding (43 characters, `[A-Za-z0-9_-]`).
- Store: SHA-256 of the token text, as 64 lowercase hex characters.
- Find: a token that is not exactly 43 allowed characters is refused **before** any database call; otherwise hash it and look up `calendar_feeds.token_hash`; the user must still be active (`UserApi.findActiveRole` not empty).

## 6. The feed (`IcsWriter`)

```
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Campus Hub//Reservations//EN
CALSCALE:GREGORIAN
METHOD:PUBLISH
X-WR-CALNAME:Campus Hub
BEGIN:VEVENT
UID:reservation-123@campus-hub
DTSTAMP:20261110T093000Z
DTSTART:20261116T080000Z
DTEND:20261116T100000Z
SUMMARY:Oscilloscope DS1054Z #2
DESCRIPTION:Campus Hub reservation #123\nModel: DS1054Z\nStatus: CONFIRMED
END:VEVENT
END:VCALENDAR
```

Rules of the format that are easy to get wrong (each has a test):

1. Lines end with **CRLF** (`\r\n`).
2. In text values, `\` `;` `,` are escaped with a backslash and a line break is written as `\n`.
3. A line longer than **75 bytes** (UTF-8) is folded: CRLF + one space, never in the middle of a multi-byte letter (`ș`, `ț`).
4. `UID` is stable (`reservation-<id>@campus-hub`): the calendar app updates the same event instead of adding a second one. This is what makes a **moved** booking change its title in place.
5. `DTSTART`/`DTEND` = the booking's campus time → `atZone(clock.getZone())` → UTC. `DTSTAMP` = now, UTC.
6. `SUMMARY` = the asset's current name; an `OVERDUE` booking gets the prefix `OVERDUE: `.

## 7. API (`docs/api-contract.md`: new section "Calendar feed (Calin)"; Matei adds the row in §2 security rules)

### Link settings (logged-in user, the id from `CurrentUser`)

| Call | Answer |
|---|---|
| `GET /api/v1/reservations/me/calendar-feed` | `200 { enabled: boolean }` |
| `POST /api/v1/reservations/me/calendar-feed` | `201 { path: "/api/v1/calendar/<token>.ics" }`. Replaces an existing link (the old one stops working). Audit `CALENDAR_FEED_CREATED` |
| `DELETE /api/v1/reservations/me/calendar-feed` | `204`, also when there was no link. Audit `CALENDAR_FEED_REVOKED` only when one existed |

### `GET /api/v1/calendar/{token}.ics` (public)

- `200`, `Content-Type: text/calendar; charset=UTF-8`, `Cache-Control: no-store`, body as in §6.
- Unknown, replaced or turned-off token, a malformed token, or a disabled user → `404 CALENDAR_FEED_NOT_FOUND` (one answer for all).

### `SecurityConfig` (Matei's PR)

- New rule above `/api/v1/**`: `GET /api/v1/calendar/*` → `permitAll`. Other methods on that path still need a login.
- The token-header resolver also skips `/api/v1/calendar/**` (same reason as the #116 fix: an old `Authorization` header must not turn a public path into a 401).

## 8. Frontend (Calin's page, Stefania's conventions)

- `src/api/calendarFeed.ts`: `fetchCalendarFeed()`, `createCalendarFeed()`, `revokeCalendarFeed()`.
- `CalendarSyncPage.tsx` (`/calendar-sync`):
  - no link yet: short explanation + **Create calendar link**;
  - just created: the full address (`window.location.origin + path`) in a read-only field with a **Copy** button, a **Download .ics** link, and the warning "Anyone with this link can see your bookings. We show it only now.";
  - link exists (after a reload): "A calendar link is active" + **Make a new link** (asks to confirm: the old one stops working) + **Turn off**;
  - three short how-tos (Google Calendar: *Other calendars → From URL*; Apple Calendar: *File → New Calendar Subscription*; Outlook: *Add calendar → Subscribe from web*) and the note "Calendar apps refresh on their own schedule; Google can take several hours."
- Every load resets its error and stops its spinner in `finally`.

## 9. Security notes (for the defense)

| Question | Answer |
|---|---|
| Why is an endpoint without login acceptable? | The token is 256 random bits, as hard to guess as a session key. It opens one thing: a read-only list of that user's bookings (asset names and times, no e-mail, no other user) |
| What if the link leaks? | "Make a new link" or "Turn off": the old one answers 404 at once |
| What if the database leaks? | Only hashes are stored; a hash can't be turned back into a link |
| Does the token end up in logs? | Our app doesn't log request paths. When CloudFront/access logging is switched on in W7, paths would be logged: keep it off for `/api/v1/calendar/*` or accept it and write it in the runbook |
| Disabled student? | The lookup requires an active account (`UserApi.findActiveRole`): 404 |

## 10. Testing (AGENTS.md §5)

| Test | Kind | Covers |
|---|---|---|
| `IcsWriterTest` | plain JUnit | the six rules of §6; winter time (UTC+2) and summer time (UTC+3); an empty calendar is still valid |
| `CalendarTokenServiceTest` | Mockito (`CalendarFeedRepository`, `UserApi`, `CurrentUser`, `AuditService`) | only the hash is saved; a second create replaces the first; revoke deletes the row; malformed token → empty without a database call; disabled user → empty; audit rows |
| `CalendarFeedRepositoryTest` | `@DataJpaTest` | lookup by hash; the same hash twice is refused; a second row for the same user is refused |
| `ReservationRepositoryTest` (new tests) | `@DataJpaTest` | the by-user range query: overlap at both edges, other users left out, any status returned, ordered by start |
| `CalendarFeedServiceTest` | Mockito (`CalendarTokenService`, `ReservationRepository`, `CatalogApi`), fixed `Clock` | window = now −30 d … +30 d; hidden statuses left out; `RETURNED` kept; the asset name is the current one; one catalog call per asset; unknown token → `CALENDAR_FEED_NOT_FOUND` |
| Controller slices | `@WebMvcTest` | settings need a login; the feed needs none; content type; 404 code |
| `SecurityConfigTest` (new tests, Matei's PR) | existing class | feed path open without a token and with a stale token; `POST` on it still 401; every other `/api/v1/**` path still 401 |

Manual: import the link in one real calendar app on AWS after the W7 deploy (Google can't reach `localhost`); locally, open the downloaded `.ics`.

## 11. Delivery (one PR each, one personal doc each)

| PR | Branch | Content | Waits on |
|---|---|---|---|
| 1 | `feat/reservation-calendar-token` | migration, `CalendarFeed`, `CalendarFeedRepository`, `CalendarTokenService`, settings endpoints, tests | – |
| – | Matei: `feat/config-calendar-feed-public` | `SecurityConfig` rule + resolver skip, `SecurityConfigTest`, api-contract §2 row | – |
| 2 | `feat/reservation-calendar-feed` | range query, `IcsWriter`, `CalendarFeedService`, `CalendarFeedController`, api-contract section, tests | PR 1; Matei's rule on `main` |
| 3 | `feat/frontend-calendar-sync` | `calendarFeed.ts`, `CalendarSyncPage.tsx`, route + menu | PR 2; Stefania's OK |

**Cut line:** all three (and Matei's rule) are needed for the feature to be visible. Matei's rule is small and needs nothing from Calin, so it should land first.

## 12. Out of scope (YAGNI)

A location field (needs the room name), reminders/alarms in the events, several links per user, an expiry date on links, an admin feed of all bookings, write access (creating bookings from the calendar), a rate limit on the feed.

# 41. Calendar Feed Endpoint & Service

**Author:** Călin Murariu (@Swiorx)  
**Date:** 2026-11-16  
**Branch:** `feat/reservation-calendar-feed`  
**Refs:** #289

---

## Part 1: In simple terms

Once a student has created a secret calendar link (from PR 1), external calendar apps such as Google Calendar, Apple Calendar, or Outlook periodically request that link to download the student's latest bookings.

This change wires up the live `.ics` subscription endpoint:
1. **Range query (`ReservationRepository`):** Fetches all of the student's bookings overlapping a 60-day window (`30 days back` to `30 days ahead` from now), ordered chronologically.
2. **Feed builder (`CalendarFeedService`):** Turns the secret token into an active student ID, keeps only active/completed bookings (`CONFIRMED`, `CHECKED_OUT`, `OVERDUE`, `RETURNED`) so cancelled or no-show bookings disappear from the student's calendar, fetches each equipment unit's current name once from the catalog (so automatic reassignments update the calendar title in place), prefixes overdue items with `OVERDUE: `, and formats everything into standard `.ics` text using `IcsWriter`.
3. **Public endpoint (`CalendarFeedController`):** Serves `GET /api/v1/calendar/{token}.ics` with `Content-Type: text/calendar;charset=UTF-8` and `Cache-Control: no-store`, or returns `404 CALENDAR_FEED_NOT_FOUND` for any unknown, revoked, replaced, malformed, or disabled-user token.

---

## Part 2: Technical details

### 1. Repository Query (`ReservationRepository`)
- `List<Reservation> findByUserIdOverlappingRange(Long userId, LocalDateTime from, LocalDateTime to)`:
  - JPQL predicate `r.userId = :userId and r.startTime < :to and r.endTime > :from` (half-open interval overlap matching `existsOverlap`), returning all statuses ordered by `r.startTime asc`.
  - No status filtering in SQL so the query stays general and status visibility lives explicitly in `CalendarFeedService`.

### 2. Feed Assembly (`CalendarFeedService`)
- Annotated `@Service`, `@Transactional(readOnly = true)`, `@RequiredArgsConstructor`.
- **Token resolution:** `calendarTokenService.findActiveUserId(token)` → throws `NotFoundException("CALENDAR_FEED_NOT_FOUND", "Calendar link not found")` if empty.
- **Window:** `campusNow = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)`; queries `[campusNow.minusDays(30), campusNow.plusDays(30)]`.
- **Status filter:** `SHOWN_STATUSES = EnumSet.of(CONFIRMED, CHECKED_OUT, OVERDUE, RETURNED)`. Leaving `CANCELLED`, `FORCE_CANCELLED`, and `NO_SHOW` out of the `.ics` feed causes subscribing calendar apps to delete those events automatically.
- **Catalog lookup caching:** Caches `CatalogApi.getAsset(assetId)` results in a `Map<Long, Optional<AssetSummary>>` per request so each distinct asset is fetched at most once. If `CatalogApi.getAsset` throws `NotFoundException`, falls back to `"Equipment #" + assetId` and model `"Unknown"`.
- **Event mapping:**
  - `uid`: `"reservation-" + r.getId() + "@campus-hub"` (stable across reassignments so clients update the existing event).
  - `start` / `end`: `r.getStartTime().atZone(clock.getZone()).toInstant()` and `r.getEndTime().atZone(clock.getZone()).toInstant()` (converts campus `Europe/Bucharest` local times to UTC instants).
  - `summary`: `(r.getStatus() == ReservationStatus.OVERDUE ? "OVERDUE: " : "") + assetName`.
  - `description`: `"Campus Hub reservation #" + r.getId() + "\nModel: " + assetModel + "\nStatus: " + r.getStatus().name()`.

### 3. HTTP Controller (`CalendarFeedController`)
- Mapped to `/api/v1/calendar`, `GET /{token}.ics` with `produces = "text/calendar;charset=UTF-8"`.
- Returns `200 OK` with `CacheControl.noStore()`, `Content-Type: text/calendar;charset=UTF-8`, and the `.ics` body.
- Documented with `@Tag(name = "Calendar feed")`, `@Operation`, and `@ApiResponses` (`200` `text/calendar;charset=UTF-8`, `404` `CALENDAR_FEED_NOT_FOUND`), compatible with `OpenApiDocsIT`.

### 4. Security Notes (Spec §9 — Defense Q&A)

| Question | Answer |
|---|---|
| Why is an endpoint without login acceptable? | The token is 256 random bits, as hard to guess as a session key. It opens one thing: a read-only list of that user's bookings (asset names and times, no e-mail, no other user) |
| What if the link leaks? | "Make a new link" or "Turn off": the old one answers 404 at once |
| What if the database leaks? | Only hashes are stored; a hash can't be turned back into a link |
| Does the token end up in logs? | Our app doesn't log request paths. When CloudFront/access logging is switched on in W7, paths would be logged: keep it off for `/api/v1/calendar/*` or accept it and write it in the runbook |
| Disabled student? | The lookup requires an active account (`UserApi.findActiveRole`): 404 |

### 5. Tests Added
| Test Class | Type | Coverage |
|---|---|---|
| `ReservationRepositoryTest` | `@DataJpaTest` | Half-open overlap boundaries (abutting edges excluded, 1-second overlap included), all statuses returned ordered by `startTime asc`, other users excluded |
| `CalendarFeedServiceTest` | Mockito unit | Unknown token `404 CALENDAR_FEED_NOT_FOUND`, `[-30d, +30d]` window, hidden statuses excluded (`CANCELLED`, `FORCE_CANCELLED`, `NO_SHOW`), `RETURNED` kept, current asset name, `OVERDUE: ` prefix, single `CatalogApi.getAsset` call per asset, missing asset fallback (`Equipment #<id>`), empty calendar output |
| `CalendarFeedControllerTest` | `@WebMvcTest` | `200` without auth + `text/calendar;charset=UTF-8` + `Cache-Control: no-store`, `404 CALENDAR_FEED_NOT_FOUND`, odd/oversized tokens (`abc`, 5,000 `a`s) return `404` without 500 |

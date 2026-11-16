# Open the calendar feed address in `SecurityConfig` (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (owner of `config/SecurityConfig`)
**Date:** 2026-11-16
**Branch / PR:** `feat/config-calendar-feed-public` (#PR)
**See also:** `docs/superpowers/specs/2026-11-10-calendar-feed-design.md`, `docs/superpowers/plans/2026-11-10-calendar-feed.md` (Task 0), `20-auth-ignores-old-token.md`

---

## Part 1: In simple terms

1. **Why this is needed:** Calin is building the calendar feed feature so a student can paste a secret `.ics` link into Google Calendar, Apple Calendar or Outlook and see their lab bookings next to their classes.
2. **Calendar apps cannot log in:** when Google Calendar fetches that link in the background, it cannot type a password or send our 8-hour login pass (`Authorization: Bearer ...`). Instead, the 43-character random secret inside the web address (`/api/v1/calendar/<secret>.ics`) acts as the key.
3. **Opening the door for that one address:** our security guard (`SecurityConfig`) normally locks every `/api/v1/...` address behind a login pass. This PR opens `GET /api/v1/calendar/*` so calendar apps can read the feed without a login pass, while keeping every other method (`POST`, `DELETE`, ...) and every other `/api/v1/**` address locked.
4. **Ignoring an old login pass on that address:** if a student clicks "Download `.ics`" in a browser that still holds an expired login pass, the server ignores that header on `/api/v1/calendar/**` instead of rejecting the download with `401` (the same protection we added for `/api/v1/auth/**` in #116).

**Result:** Calin's `CalendarFeedController` can land in its own PR without touching `SecurityConfig`.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/src/main/java/com/campus/config/SecurityConfig.java` | `requestMatchers(HttpMethod.GET, "/api/v1/calendar/*").permitAll()` above `/api/v1/**`; `ignoreTokenOnAuthPaths()` also skips `/api/v1/calendar/**` |
| `backend/src/test/java/com/campus/config/SecurityConfigTest.java` | `calendarFeedIsPublic`, `calendarFeedIgnoresStaleToken`, `postOnCalendarPathStillNeedsLogin` |
| `docs/api-contract.md` | §2 security rules table: row 5 (`GET /api/v1/calendar/*`) + pre-rule note on `/api/v1/calendar/**` |

### Why `GET /api/v1/calendar/*` is `permitAll`

| Question | Answer |
|---|---|
| Why no JWT on this endpoint? | External calendar clients (Google Calendar, Apple Calendar, Outlook) poll subscription URLs over plain HTTP `GET` and cannot attach custom `Authorization: Bearer` headers |
| What protects the student's schedule? | A 256-bit `SecureRandom` token (43 base64url chars) in the path, hashed with SHA-256 in `calendar_feeds` and checked by Calin's `CalendarTokenService.findActiveUserId` (which also checks `UserApi.findActiveRole`, so a disabled user's feed immediately stops working) |
| Why `HttpMethod.GET` only and single-segment `*`? | The feed is read-only (`/api/v1/calendar/{token}.ics`). A `POST` or deeper path on `/api/v1/calendar/**` still falls through to `.requestMatchers("/api/v1/**").authenticated()` and requires a valid JWT |
| Why place it above `/api/v1/**`? | Spring Security checks `authorizeHttpRequests` matchers top to bottom and the first match wins |

### Why `BearerTokenResolver` skips `/api/v1/calendar/**`

`BearerTokenAuthenticationFilter` runs **before** the `authorizeHttpRequests` rules. If a request carries an expired or malformed `Authorization: Bearer ...` header, the filter rejects it with `401 UNAUTHORIZED` before `permitAll()` is ever reached (#116). Returning `null` from the `BearerTokenResolver` on `/api/v1/calendar/**` (just like `/api/v1/auth/**`) tells the filter there is no bearer token to validate, so a browser or tool that sends a stale header still reaches the feed.

### How it connects to teammates' work

| Who | Connection |
|---|---|
| Calin | Unblocks Task 4 of `docs/superpowers/plans/2026-11-10-calendar-feed.md` (`CalendarFeedController` at `GET /api/v1/calendar/{token}.ics`). Because `SecurityConfig` is Matei's file (AGENTS.md §1/§3), landing this rule first lets Calin ship his controller without cross-module edits |
| Liviu / Stefania | No changes needed |

### Tests

| Test (`SecurityConfigTest`) | What it checks |
|---|---|
| `calendarFeedIsPublic` | `GET /api/v1/calendar/<43 chars>.ics` without a token passes security and reaches MVC (`404` until Calin maps `CalendarFeedController`, not `401`/`403`) |
| `calendarFeedIgnoresStaleToken` | `GET /api/v1/calendar/<43 chars>.ics` with `Authorization: Bearer not-a-jwt` also returns `404`, not `401` |
| `postOnCalendarPathStillNeedsLogin` | `POST /api/v1/calendar/<43 chars>.ics` without a token returns `401` with `ProblemDetail` `code = "UNAUTHORIZED"` |
| `otherEndpointsNeedAToken` (existing) | `GET /api/v1/_security-probe` without a token still returns `401 UNAUTHORIZED` |

`calendarFeedIsPublic` and `calendarFeedIgnoresStaleToken` failed with `401` before the `SecurityConfig` change and pass (`404`) with it. Full `./gradlew spotlessApply build` passes.

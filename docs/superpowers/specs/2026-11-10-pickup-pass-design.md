# Pickup pass (rotating code at the lab desk): design

**Status:** proposed, 2026-11-10 (Matei's design; the team confirms the split at the next meeting). **Owner:** Matei (`user/`, the Reservations desk page).

**Split of the four new features (10 Nov):** each goes to the member who owns the data it reads: pickup pass → Matei (his `user` module, his desk page, signed with his JWT secret), calendar feed → Calin, live notifications → Liviu, equipment reliability → Stefania. Proposed until the team agrees.
**Deadline:** before the feature freeze (Mon 7 Dec, W10). Must not slow down M1 (15 Nov) or the W7 deploy.

## 1. Goal

Today the admin hands equipment to whoever says "I am student #12". Nothing proves that the person at the desk owns the account.

With the pickup pass:

- the student opens **Pickup pass** on their phone and sees a 6-digit code (later also a QR code) that **changes every 30 seconds**;
- the admin types it (later: scans it) on the Reservations desk page;
- the backend answers "this is Ana Pop, student #12" or "wrong or expired code";
- the admin then checks out or returns as today, with Calin's existing endpoints.

Success = in the demo a phone shows the code, the desk accepts it and shows the student's name, and a screenshot of the code taken a minute earlier is refused. Every rule has a unit test.

## 2. Decisions (proposed, Matei confirms)

| # | Decision | Why |
|---|---|---|
| 1 | The code belongs to the **student**, not to one booking | The desk already lists the bookings; what it lacks is proof of who is standing there. It also needs no new `ReservationApi` method |
| 2 | The code is computed, not stored: `HMAC-SHA256(server secret, "pickup-v1\|userId\|timeStep")`, cut to 6 digits | Same idea as an authenticator app. No table, no migration, nothing to clean up |
| 3 | Time step **30 s**; the server accepts the current and the previous step | The student reads the code at second 29, the admin types it 10 s later: it must still work. A code lives at most 60 s |
| 4 | The key is the existing `app.jwt.secret`; the message starts with `pickup-v1\|` | No new secret to deploy. A login token is signed over `base64.base64` text, which never contains `\|`, so one signature can never pass for the other |
| 5 | Verification is a **separate step**, not a new condition of check-out | A student with a dead phone can still be served (the admin checks the student card). Calin's endpoints stay as they are |
| 6 | A successful verification is written to the audit log (`PICKUP_PASS_VERIFIED`) | The admin audit page then shows who was verified, when and by whom |
| 7 | QR code and camera scanning come last, in their own PR | They need two frontend libraries (`package.json` is Stefania's). The typed code works without them |

## 3. Ownership: what we build vs. what we ask for

We never write teammates' code (AGENTS.md §3). We only ask.

| Who | What |
|---|---|
| Matei | `user/service/PickupCodes`, `PickupPassService`; `user/web/PickupPassController`, `AdminPickupPassController` + records; `frontend/src/api/pickupPass.ts`, `PickupPassPage.tsx`, the verify dialog on `ReservationsDeskPage.tsx`; api-contract section; personal docs |
| Stefania | OK for the route `pickup-pass` + menu entry "Pickup pass" under "Mine" (`App.tsx`, `layout/navigation.ts`); for PR 3: add a QR drawing library and a QR reading library to `package.json` (suggestion: `qrcode.react` and `jsqr`, she picks) |
| Liviu | Only if he wants it: add `PICKUP_PASS_VERIFIED` to the action list of the audit log page filter (the row shows up in the table without it) |
| Calin | Nothing. The desk keeps calling his `check-out` / `return` and uses the `userId` filter his admin search already has |

## 4. Architecture

```
com.campus.user
  service/  PickupCodes           pure function: (userId, timeStep) -> "493027". No Spring, no clock.
            PickupPassService     current pass for the logged-in user; verify(userId, code) for the desk
  web/      PickupPassController        GET  /api/v1/users/me/pickup-pass
            AdminPickupPassController   POST /api/v1/admin/pickup-pass/verify
            PickupPassResponse, VerifyPickupPassRequest, PickupPassVerifiedResponse   (records)
```

- Everything stays inside `user` (Matei's module): no new module, no new dependency, no ArchUnit change.
- "Now" comes from the shared `Clock` (`clock.instant().getEpochSecond()`), so tests use a fixed clock.
- No `SecurityConfig` change: `/api/v1/users/**` needs a login, `/api/v1/admin/**` needs ADMIN.

## 5. The code (`PickupCodes`)

```
timeStep = epochSecond / 30                      (whole division)
mac      = HMAC-SHA256(key = app.jwt.secret bytes, message = "pickup-v1|" + userId + "|" + timeStep)
number   = first 4 bytes of mac as an int, sign bit cleared
code     = number % 1_000_000, written with leading zeros to 6 digits
```

- `validForSeconds = 30 - (epochSecond % 30)`: 30 at the start of a step, 1 at its last second.
- Verify: the code equals `code(userId, step)` or `code(userId, step - 1)`. Compared with `MessageDigest.isEqual` (takes the same time for a right and a wrong code).
- The QR text is `campus-pickup:1:<userId>:<code>` (prefix, format version, user id, code).

Constants at the top of `PickupCodes`, each with a one-line *why*: `STEP_SECONDS = 30`, `DIGITS = 6`, `PURPOSE = "pickup-v1"`.

## 6. API (`docs/api-contract.md` gets a "Pickup pass (Matei)" section)

### `GET /api/v1/users/me/pickup-pass` (any logged-in user)

`200` → `PickupPassResponse { code: "493027", qrText: "campus-pickup:1:12:493027", validForSeconds: 17 }`. `code` is a **string** (leading zeros). The user id comes from `CurrentUser`.

### `POST /api/v1/admin/pickup-pass/verify` (ADMIN)

Body `VerifyPickupPassRequest { userId: 12, code: "493027" }` (`userId` required; `code` exactly 6 digits, else `400 VALIDATION_FAILED`).

- `200` → `PickupPassVerifiedResponse { userId, fullName, email }` and one audit row: actor = the admin, action `PICKUP_PASS_VERIFIED`, entity `USER` + the student's id.
- `409 PICKUP_CODE_INVALID` ("The code is wrong or has expired") for a wrong code, an old code, an unknown user and a disabled user. One answer for all four, so the endpoint doesn't reveal which accounts exist. Thrown as `BusinessRuleException`.

## 7. Frontend (Matei's pages, Stefania's conventions)

- `src/api/pickupPass.ts`: `fetchPickupPass()`, `verifyPickupPass(userId, code)`, `parseQrText(text)` (returns `{ userId, code }` or `null`).
- `PickupPassPage.tsx` (`/pickup-pass`): the code in large digits ("493 027"), a ring that empties in step with `validForSeconds`, a new fetch when it reaches 0 **and** when the tab becomes visible again (phones pause timers while the screen is off). Error state with "Try again". PR 3 adds the QR code above the digits.
- `ReservationsDeskPage.tsx`: a **Verify** button on each "To pick up" row → dialog with a 6-box code input → `verifyPickupPass(row.userId, code)`. On success the Student column of that student's rows shows the full name and a green "verified" mark (kept in page state, lost on reload). On `PICKUP_CODE_INVALID` the dialog shows the message and clears the boxes. Spaces typed by the admin are removed before sending.
- PR 3: a **Scan pass** button in the desk header opens the camera, reads the QR, calls verify, then filters the list to that student (`userId` filter of Calin's admin search) with a "Showing Ana Pop's bookings · Clear" bar.
- Every load resets its error and stops its spinner in `finally`.

## 8. Security notes (for the defense)

| Question | Answer |
|---|---|
| Can a student guess someone else's code? | Only admins can call verify, and a student never sees another student's code |
| Can an admin guess codes? | 2 of 1,000,000 codes are valid at a time. An admin gains nothing: check-out without a code is still allowed (decision 5) |
| Can a screenshot be reused? | For at most 60 s. After that it is refused. One-time use would need a table (§10) |
| What if the server restarts? | Nothing changes: the code depends only on the secret, the user id and the time |
| What if a student is disabled? | Verify answers `PICKUP_CODE_INVALID` at once (the account is read on every call) |

## 9. Testing (AGENTS.md §5: one rule per test, `.as(...)` naming the rule and the class, fixed `Clock`)

| Test | Kind | Covers |
|---|---|---|
| `PickupCodesTest` | plain JUnit | same input → same code; changes with the step; differs per user; differs per secret; always 6 digits with leading zeros; one pinned value (catches an accidental change of the formula) |
| `PickupPassServiceTest` | Mockito (`UserRepository`, `AuditService`, `CurrentUser`), fixed `Clock` | seconds left at second 0, 10 and 29 of a step; accepts current and previous step; rejects two steps old, next step, wrong code, unknown user, disabled user (all `PICKUP_CODE_INVALID`); audit row on success only |
| `PickupPassControllerTest`, `AdminPickupPassControllerTest` | `@WebMvcTest` | 401 without token; student gets 403 on verify; 5 digits, letters or missing `userId` → 400; 409 code; 200 shapes; `code` is a JSON string |

Frontend: `npm run lint && npm run build`, screenshots in the PR. Manual check on a real phone against the laptop.

## 10. Delivery (one PR each, one personal doc each)

| PR | Branch | Content | Waits on |
|---|---|---|---|
| 1 | `feat/user-pickup-pass` | `PickupCodes`, `PickupPassService`, both endpoints, tests, api-contract | – |
| 2 | `feat/frontend-pickup-pass` | `pickupPass.ts`, `PickupPassPage.tsx`, Verify dialog on the desk, route + menu | PR 1; Stefania's OK for route + menu |
| 3 | `feat/frontend-pickup-qr` | QR on the pass page, camera scan on the desk | PR 2; Stefania's two libraries |

**Cut line:** PRs 1 and 2 are the feature. PR 3 is the nice demo; drop it if the libraries are not in by 30 Nov.

## 11. Out of scope (YAGNI)

One-time use of a code (needs a table), a code per booking, self check-out without an admin, attempt limits on verify, failed attempts in the audit log, verification as a hard condition of check-out.

# Pickup pass, backend: a code that changes every 30 seconds (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (owner of the `user` module)
**Date:** 2026-11-11
**Branch / PR:** `feat/user-pickup-pass` (#PICKUP1), PR 1 of 3 (pages come next)
**See also:** `docs/superpowers/specs/2026-11-10-pickup-pass-design.md`, `docs/api-contract.md` ("Pickup pass (Matei)")

---

## Part 1: In simple terms

Think of the app of a bank that shows a 6-digit number which changes every half minute. Only you and the bank can work out that number.

1. **The student opens "Pickup pass"** (the page comes in PR 2) and the backend answers with a 6-digit code and how many seconds it stays valid.
2. **At the lab desk the admin types that code**, together with the student's number.
3. **The backend does the same sum** and compares. If it matches, it answers "this is Ana Pop" and writes a line in the audit log ("admin X verified Ana Pop"). If not, it answers "the code is wrong or has expired".
4. **A screenshot is useless after a minute.** The code of the last half minute is still accepted (the student reads it at second 29, the admin types it 10 seconds later), but nothing older.
5. **Nothing is stored.** No new table: the server recomputes the code each time, so a restart changes nothing.
6. **The desk is not blocked without a code.** A student with a dead phone can still be served; Calin's check-out and return work as before.

**Result:** two new addresses: the student asks for the code, the admin verifies it. Students cannot verify codes (they get "not allowed").

---

## Part 2: Technical details

### Files

| File | What it is |
|---|---|
| `user/service/PickupCodes.java` | The formula (package-private, no Spring) |
| `user/service/PickupPassService.java` | `currentPass()` and `verify(userId, code)` |
| `user/web/PickupPassController.java` | `GET /api/v1/users/me/pickup-pass` |
| `user/web/AdminPickupPassController.java` | `POST /api/v1/admin/pickup-pass/verify` |
| `user/web/VerifyPickupPassRequest.java` | `@NotNull Long userId`, `@NotNull @Pattern("\\d{6}") String code` |
| `user/web/PickupPassResponse.java`, `PickupPassVerifiedResponse.java` | The two answers |
| `PickupCodesTest`, `PickupPassServiceTest`, `PickupPassControllerTest`, `AdminPickupPassControllerTest` | Tests |
| `docs/api-contract.md` | New section "Pickup pass (Matei)" + error code `PICKUP_CODE_INVALID` |

### The formula and why each part exists

```
timeStep = epochSecond / 30
mac      = HMAC-SHA256(key = app.jwt.secret bytes, "pickup-v1|" + userId + "|" + timeStep)
code     = (first 4 bytes of mac as int, sign bit cleared) % 1_000_000, padded to 6 digits
```

| Piece | Why |
|---|---|
| `STEP_SECONDS = 30` | Short enough that a screenshot dies fast, long enough to read and type |
| Accept step and step - 1 | Reading at second 29 and typing 10 s later must work; a code lives at most 60 s |
| `DIGITS = 6` | Easy to read aloud and type; same as authenticator apps |
| `PURPOSE = "pickup-v1"` | A login token is signed over `base64.base64` text, which never contains `\|`, so a signature of one kind can never pass for the other. Also leaves room for a "v2" |
| Key = `app.jwt.secret` | No new secret to deploy; a secret under 32 bytes already stops the app at startup (doc 23, `docs/report/security.md`) |
| `Clock` instead of `Instant.now()` | Fixed clock in tests (team rule) |
| `MessageDigest.isEqual` | Same time for a right and a wrong code |
| One error for all failures | Wrong code, old code, unknown user, disabled user all give `409 PICKUP_CODE_INVALID`: nobody can probe which accounts exist |
| `@Pattern` on `code` | `verify` assumes a non-null 6-digit string; the request record guarantees it |

### Security notes (spec §8)

| Question | Answer |
|---|---|
| Can a student guess someone else's code? | Only admins can call verify, and a student never sees another student's code |
| Can an admin guess codes? | 2 of 1,000,000 codes are valid at a time; an admin gains nothing, since check-out without a code is still allowed |
| Can a screenshot be reused? | For at most 60 s; one-time use would need a table (out of scope) |
| Server restart? | No effect: the code depends only on secret, user id and time |
| Disabled student? | `PICKUP_CODE_INVALID` at once, the account is read on every call |

### How it connects to teammates' work

| Who | How |
|---|---|
| Calin | Nothing changes: the desk page calls his `check-out` / `return` as before. The verify result only shows the student's name |
| Liviu | The audit row (`PICKUP_PASS_VERIFIED`, entity `USER`) already shows in his audit page; adding the action to its filter list is optional |
| Stefania | Route and menu entry come with PR 2 (needs her OK) |

### Tests

| Test | Result |
|---|---|
| `PickupCodesTest` (9), `PickupPassServiceTest` (12) | green (earlier tasks) |
| `PickupPassControllerTest` (3): 401 without token, 200 shape, `code` is a JSON string | green |
| `AdminPickupPassControllerTest` (7): student 403, 200 with name, 409 code, 5 digits / letters / `"493 027"` / missing `userId` all 400 `VALIDATION_FAILED` with the service never called | green |

The controller tests first failed to compile (the controllers did not exist). They use `jwt()` with a `ROLE_` authority like `UserControllerTest`, so the real `/api/v1/admin/**` rule is exercised. Full build: `./gradlew spotlessApply build` ends in BUILD SUCCESSFUL (1034 tests, 0 failures; ArchUnit admin-prefix rules included).

### Real-app check (dev profile, scratch PostgreSQL, `bootRun`)

Logins as `student1@campus.test` (id 1) and `admin@campus.test`, password `password`.

| Request | Answer |
|---|---|
| `GET /users/me/pickup-pass`, no token | `401` `UNAUTHORIZED` |
| `GET /users/me/pickup-pass`, student | `200` `{"code":"950632","qrText":"campus-pickup:1:1:950632","validForSeconds":21}` |
| `POST /admin/pickup-pass/verify`, student token | `403` `FORBIDDEN` |
| same, admin token, `{"userId":1,"code":"950632"}` | `200` `{"userId":1,"fullName":"Student One","email":"student1@campus.test"}` |
| admin, code `12345` | `400` `VALIDATION_FAILED`, `errors[0]` = `code must be exactly 6 digits` |
| admin, code `000000` / unknown user 9999 | `409` `PICKUP_CODE_INVALID` (same body for both) |
| `GET /admin/audit-logs?action=PICKUP_PASS_VERIFIED` | 1 row: actor 3 (admin), entity `USER` 1, "Pickup pass verified for Student One" |
| same code after 70 s | `409` `PICKUP_CODE_INVALID`; audit still 1 row |

### Still open

PR 2: the pickup pass page and the Verify dialog on the desk page (needs Stefania's OK for the route and menu entry). PR 3: QR code and camera scan.

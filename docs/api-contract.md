# API contract

The single source of truth for the REST API: conventions, security rules, auth, error format and every error `code`.
**Update this file in the same PR as any API change.** Stefania's frontend types and messages are built from it.

Status: **draft (2026-10-21)**. Sections 1–4 are Matei's; the error format (§4) must be confirmed with Liviu, who owns `GlobalExceptionHandler`. Module owners add their endpoints in §6.

---

## 1. Conventions

| Rule | Example |
|---|---|
| Base path `/api/v1`, plural nouns | `GET /api/v1/assets` |
| Admin-only endpoints under `/api/v1/admin/**` | `POST /api/v1/admin/assets` (security protects the prefix, see §2) |
| State changes are sub-resources | `POST /api/v1/reservations/{id}/cancel` |
| JSON in camelCase, numeric IDs | `{"assetId": 12}` |
| Date-times: ISO-8601 **campus local time, no offset** (Europe/Bucharest) | `"2026-11-03T14:00:00"`. The frontend sends `dayjs(d).format('YYYY-MM-DDTHH:mm:ss')`, **never** `toISOString()` (that converts to UTC with a `Z`) |
| Lists are paginated | `?page=0&size=20&sort=name,asc` (`size` ≤ 100, sort fields are whitelisted per endpoint) |
| Paginated response | `PageResponse<T>`: `{ "content": [...], "page": 0, "size": 20, "totalElements": 57, "totalPages": 3 }` |
| Other people's resources | **404**, not 403, so IDs of others are not revealed |
| The current user | always from the JWT (`CurrentUser.id()`), **never** from the request body |

**Status codes:** `200` OK · `201` created (+ body) · `204` no content · `400` validation / malformed request · `401` not logged in or token invalid/expired · `403` logged in but not allowed · `404` not found · `409` conflict, invalid state transition, limit reached · `413` upload too large · `500` unexpected (generic message only).

---

## 2. Security rules

Checked top to bottom in `SecurityConfig`; the **first match wins**. "Refused" says what a caller gets when the rule says no: **401** = no token or a rejected token, **403** = valid token but not allowed. Every refusal is a `ProblemDetail` (§4) with the `code` shown.

| # | Path | Access | Refused |
|---|---|---|---|
| 1 | `/api/v1/auth/**` (ping, register, login) | public; the `Authorization` header is **not read** here, so an old or broken token never blocks login (#116) | – |
| 2 | `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | public (health check, Swagger UI) | – |
| 3 | `/error` | public: Spring forwards failures here; protecting it would turn a 404 into a 401 | – |
| 4 | `/api/v1/admin/**` | role `ADMIN`. Must stay above rule 5, or rule 5 would let students in | `401 UNAUTHORIZED` · student: `403 FORBIDDEN` |
| 5 | `/api/v1/**` | any logged-in, active user (`STUDENT` or `ADMIN`) | `401 UNAUTHORIZED` |
| 6 | `/api/**`, `/actuator/**` (other API versions, every actuator endpoint except health) | denied, even for an admin. Must stay above rule 7 | `401 UNAUTHORIZED` · with a token: `403 FORBIDDEN` |
| 7 | `GET /**` | public: the React app served by `SpaConfig` in prod (ADR-002: `index.html`, JS files, page routes like `/reservations`). It holds no data; all data comes from `/api` | – |
| 8 | anything else (e.g. `POST /something-else`) | denied: a path nobody listed fails closed | `401 UNAUTHORIZED` · with a token: `403 FORBIDDEN` |

Checks that run **before** the rules above (the token checks only when a token is sent, on every path except `/api/v1/auth/**`):

| Check | Result |
|---|---|
| Firewall: the URL contains `;`, `..` or an encoded `/` (e.g. `/api/v1/auth/ping;x=1`) | `400 REQUEST_REJECTED` (#118) |
| Token signature wrong, token expired or not a JWT | `401 UNAUTHORIZED` |
| Token `sub` missing or not a number | `401 UNAUTHORIZED` (#158; was a 500) |
| Token valid, but the account is disabled or deleted | `401 UNAUTHORIZED` on the next request: the account is looked up on every request (`UserApi.findActiveRole`, #117) |
| Role | read from the **database** on every request, not from the token's `roles` claim, so a role change counts at once (#117) |

- The API is **stateless**: no session, no cookies, every request carries `Authorization: Bearer <token>`. CSRF protection is off for that reason.
- Frontend and API share one origin (Vite proxy in dev, CloudFront in prod), so **no CORS**.
- Ownership checks (a student sees/changes only their own reservations and incidents) are done in each module's **service**, not here.

---

## 3. Authentication (JWT)

### Endpoints

| Method & path | Access | Request | Response |
|---|---|---|---|
| `GET /api/v1/auth/ping` | public | – | `200 {"message":"pong"}` |
| `POST /api/v1/auth/register` | public | `{"email","password","fullName"}` | `201` + `UserResponse`. Always creates a **`STUDENT`**; admins can never self-register |
| `POST /api/v1/auth/login` | public | `{"email","password"}` | `200` + `LoginResponse` |
| `GET /api/v1/users/me` | logged in | – | `200` + `UserResponse` of the token's user (`401 UNAUTHORIZED` without a token, or if the account was disabled or deleted: the token check runs before the controller since #128) |

```json
// LoginResponse
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 28800,
  "user": { "id": 1, "email": "student1@campus.test", "fullName": "Student One", "role": "STUDENT" }
}
// UserResponse
{ "id": 1, "email": "student1@campus.test", "fullName": "Student One", "role": "STUDENT" }
```

### Rules

| Rule | Value |
|---|---|
| Email | trimmed, stored lowercase, unique → `409 EMAIL_TAKEN` |
| Password | 8–72 characters **and** at most 72 bytes (BCrypt's limit; "ș" counts as 2) → else `400 VALIDATION_FAILED` 🟡 confirm |
| Full name | 1–100 characters |
| Wrong email **or** wrong password | the **same** `401 INVALID_CREDENTIALS`, so nobody can test which emails exist |
| Disabled user | `401 INVALID_CREDENTIALS` as well |
| Token | HS256-signed JWT, lifetime **8 h**, no refresh token (known limitation, documented in the report) |
| Expired/invalid token | `401 UNAUTHORIZED`: the frontend clears the token and redirects to the login page |
| Account disabled or deleted after login | `401 UNAUTHORIZED` on the next request: every request checks the account (#117) |

### Token claims

| Claim | Example | Used by |
|---|---|---|
| `sub` | `"1"` (user id as a string) | `CurrentUser.id()` (Stefania) |
| `email` | `"student1@campus.test"` | display only |
| `roles` | `["STUDENT"]` or `["ADMIN"]` | information only: since #117 the backend reads the role from the database on every request, so a role change counts at once |
| `iat`, `exp` | epoch seconds | expiry check |

Don't rename claims after W3: `CurrentUser` and the frontend depend on them.

### Dev seed accounts (dev profile only)

`admin@campus.test`, `student1@campus.test`, `student2@campus.test`, all with password `password`. Swagger: log in, copy `accessToken`, click **Authorize**.

---

## 4. Error format

Every error is an **RFC 9457 `ProblemDetail`** JSON object with an extra **`code`** property. Controllers never build error bodies: services throw the shared exceptions from `common.error` and Liviu's `GlobalExceptionHandler` converts them. 401 and 403 from the security filters never reach that handler, so Matei's `SecurityConfig` writes the **same shape** itself.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Asset 12 is already booked between 14:00 and 16:00",
  "instance": "/api/v1/reservations",
  "code": "RESERVATION_CONFLICT"
}
```

Validation errors (400) add a list of field errors:

```json
{
  "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "Validation failed", "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "errors": [ { "field": "email", "message": "must be a well-formed email address" } ]
}
```

| Exception (`common.error`) | Status |
|---|---|
| `BadRequestException(code, msg)`, `InvalidParameterException(msg)` | 400 |
| `UnauthorizedException(code, msg)` | 401 |
| `ForbiddenException(code, msg)` | 403 |
| `NotFoundException(code, msg)` | 404 |
| `ConflictException(code, msg)` | 409 |
| `BusinessRuleException(code, msg)` | 409 |
| `InvalidStateTransitionException(code, msg)` | 409 |
| `PayloadTooLargeException(code, msg)` | 413 |

The frontend shows a message per `code` (fall back to `detail`). Never put stack traces, SQL or class names in `detail`.

---

## 5. Error codes

### Generic (Liviu's handler + Matei's security)

| Code | Status | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | `@Valid` failed; see `errors` |
| `MALFORMED_REQUEST` | 400 | unreadable JSON, wrong date format |
| `INVALID_PARAMETER` | 400 | bad or missing query parameter, e.g. unknown `sort` field or missing required param |
| `BAD_REQUEST` | 400 | generic Spring MVC client error |
| `REQUEST_REJECTED` | 400 | the URL was blocked by Spring Security's firewall (`;`, `..`, encoded `/` in the path) |
| `UNAUTHORIZED` | 401 | no token, invalid or expired token |
| `FORBIDDEN` | 403 | logged in, but not allowed (e.g. a student on `/api/v1/admin/**`) |
| `NOT_FOUND` | 404 | endpoint or path does not exist |
| `METHOD_NOT_ALLOWED` | 405 | HTTP method not supported on this endpoint |
| `STALE_VERSION` | 409 | someone else changed the row first (optimistic locking): reload and retry |
| `PHOTO_TOO_LARGE` | 413 | upload over 5 MB |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | request `Content-Type` not supported |
| `INTERNAL_ERROR` | 500 | anything unexpected (details only in the server log) |

### Auth and users (Matei, Stefania)

| Code | Status | Owner |
|---|---|---|
| `EMAIL_TAKEN` | 409 | Matei |
| `INVALID_CREDENTIALS` | 401 | Matei |
| `USER_NOT_FOUND` | 404 | Stefania |
| `CANNOT_MODIFY_SELF` | 409 | Stefania |
| `LAST_ADMIN_REQUIRED` | 409 | Stefania |

### Catalog (Liviu)

`ASSET_NOT_FOUND` 404 · `CATEGORY_NOT_FOUND` 404 · `LAB_ROOM_NOT_FOUND` 404 · `SERIAL_NUMBER_TAKEN` 409 · `CATEGORY_NAME_TAKEN` 409 · `LAB_ROOM_CODE_TAKEN` 409 · `CATEGORY_IN_USE` 409 · `LAB_ROOM_IN_USE` 409 · `ASSET_RETIRED` 409 · `INVALID_ASSET_STATUS_TRANSITION` 409

### Reservations (Calin)

`RESERVATION_NOT_FOUND` 404 · `HOLD_NOT_FOUND` 404 · `TRANSFER_NOT_FOUND` 404 · `BUNDLE_NOT_FOUND` 404 · `RESERVATION_CONFLICT` 409 · `SLOT_HELD` 409 · `HOLD_EXPIRED` 409 · `ACTIVE_HOLD_LIMIT_REACHED` 409 · `HOLD_COOLDOWN_ACTIVE` 409 · `INVALID_HOLD_STATUS_TRANSITION` 409 · `ASSET_NOT_BOOKABLE` 409 · `RESERVATION_LIMIT_REACHED` 409 · `BOOKING_TOO_FAR_AHEAD` 409 · `BOOKING_IN_PAST` 409 · `INVALID_TIME_RANGE` 409 · `INVALID_DURATION` 409 · `OUTSIDE_OPENING_HOURS` 409 · `SLOT_NOT_ALIGNED` 409 · `CANCELLATION_NOT_ALLOWED` 409 · `CHECKOUT_WINDOW_CLOSED` 409 · `INVALID_RESERVATION_STATUS_TRANSITION` 409 · `CANNOT_TRANSFER_TO_SELF` 409 · `TRANSFER_ALREADY_PENDING` 409 · `TRANSFER_NOT_ALLOWED` 409 · `TRANSFER_LIMIT_REACHED` 409 · `INVALID_TRANSFER_STATUS_TRANSITION` 409 · `BUNDLE_TRANSFER_NOT_ALLOWED` 409 · `BUNDLE_EXTEND_NOT_ALLOWED` 409 · `MISSING_PARAMETER` 409 · `CONFLICTING_PARAMETERS` 409

### Incidents (Stefania)

`INCIDENT_NOT_FOUND` 404 · `PHOTO_NOT_FOUND` 404 · `INVALID_PHOTO_TYPE` 400 · `INVALID_INCIDENT_STATUS_TRANSITION` 409 (+ `ASSET_NOT_FOUND`, `ASSET_RETIRED` and `PHOTO_TOO_LARGE` above)

### Projects (Matei)

`PROJECT_NOT_FOUND` 404 · `PROJECT_COMPONENT_UNAVAILABLE` 409 (+ `VALIDATION_FAILED` 400, `INVALID_PARAMETER` 400, `MALFORMED_REQUEST` 400 above; `POST /projects/{slug}/reserve` also passes on the Reservations codes from `bookBundle`)

### Pickup pass (Matei)

`PICKUP_CODE_INVALID` 409 (wrong or expired code, unknown user, disabled user: one answer for all four) (+ `VALIDATION_FAILED` 400 above)

Owners: check the status codes above when you implement them, and fix this list if yours differ.

---

## 6. Endpoints per module

Each owner adds their endpoints here (method + path, access, request/response JSON example, error codes) in the same PR as the code. Drafts are in each member guide §4.

| Module | Owner | Base paths |
|---|---|---|
| Auth / users | Matei (auth), Stefania (admin) | `/api/v1/auth/**`, `/api/v1/users/me`, `/api/v1/admin/users/**` |
| Catalog / Shared | Liviu | `/api/v1/assets`, `/api/v1/categories`, `/api/v1/lab-rooms`, `/api/v1/admin/assets`, `/api/v1/admin/categories`, `/api/v1/admin/lab-rooms`, `/api/v1/admin/audit-logs` |
| Reservations | Calin | `/api/v1/reservations/**`, `/api/v1/admin/reservations/**`, `/api/v1/admin/reassignments` |
| Incidents | Stefania | `/api/v1/incidents/**`, `/api/v1/admin/incidents/**` |
| Projects | Matei | `/api/v1/projects/**` |

### User admin endpoints (Stefania)

All under `/api/v1/admin/users`, admin only (401 without a token, 403 for a student). `AdminUserResponse`: `{"id","email","fullName","role","enabled","createdAt"}`, never the password hash.

| Method & path | Access | Request | Response | Errors |
|---|---|---|---|---|
| `GET /api/v1/admin/users?q=&role=&enabled=&page=0&size=20` | admin | – | `200` + `PageResponse<AdminUserResponse>`, newest first (ties by id). `q` matches part of the email or full name, case-insensitive, max 100 chars (`%` and `_` are literal); `role` `STUDENT`/`ADMIN` and `enabled` `true`/`false` are optional. `page` ≥ 0, `size` 1–100 | 400 `INVALID_PARAMETER`, 401, 403 |
| `GET /api/v1/admin/users/{id}` | admin | – | `200` + `AdminUserResponse` | 400 `INVALID_PARAMETER`, 401, 403, 404 `USER_NOT_FOUND` |
| `POST /api/v1/admin/users/{id}/disable` | admin | – | `200` + `AdminUserResponse` with `enabled: false`. The user's login fails and an existing token gets 401 on the next request. Twice → unchanged | 401, 403, 404 `USER_NOT_FOUND`, 409 `CANNOT_MODIFY_SELF` (yourself), 409 `LAST_ADMIN_REQUIRED` |
| `POST /api/v1/admin/users/{id}/enable` | admin | – | `200` + `AdminUserResponse` with `enabled: true`. Twice → unchanged | 401, 403, 404 `USER_NOT_FOUND` |
| `PUT /api/v1/admin/users/{id}/role` | admin | `{"role": "ADMIN"}` | `200` + `AdminUserResponse`. The new role counts from the user's next request (no new login). Same role → unchanged | 400 `VALIDATION_FAILED` (no role), 400 `MALFORMED_REQUEST` (unknown role), 401, 403, 404 `USER_NOT_FOUND`, 409 `CANNOT_MODIFY_SELF` (demoting yourself), 409 `LAST_ADMIN_REQUIRED` |

Rules (decisions.md, "User admin"): an admin can't disable or demote themselves; at least one enabled admin always remains (the enabled admins are locked in id order before the check, so two admins acting on each other at the same moment can't both succeed). Every real change is written to the audit log: `USER_DISABLED`, `USER_ENABLED`, `USER_ROLE_CHANGED` (`entityType` `USER`).

### Incident endpoints (Stefania)

| Method & path | Access | Request | Response | Errors |
|---|---|---|---|---|
| `POST /api/v1/incidents` | logged in | `{"assetId","description","severity"}` | `201` + `IncidentResponse`. The asset becomes `UNDER_MAINTENANCE` | 400 `VALIDATION_FAILED`, 401, 404 `ASSET_NOT_FOUND`, 409 `ASSET_RETIRED` |
| `GET /api/v1/incidents/me?page=0&size=20` | logged in | – | `200` + `PageResponse<IncidentResponse>`: only the incidents I reported, newest first (ties by id). `page` ≥ 0, `size` 1–100 | 400 `INVALID_PARAMETER` (bad `page`/`size`, or `page × size` too big), 401 |
| `GET /api/v1/incidents/{id}` | reporter or admin | – | `200` + `IncidentResponse` | 400 `INVALID_PARAMETER`, 401, 404 `INCIDENT_NOT_FOUND` (also when it is someone else's incident, so ids are not revealed) |
| `POST /api/v1/incidents/{id}/photo` | reporter or admin | `multipart/form-data`, one part `file` (JPEG, PNG or WebP, max 5 MB) | `200` + `IncidentResponse` with the new `photoKey`. A second upload replaces the photo (the old file is deleted after the commit). Only while `OPEN` or `IN_REPAIR` | 400 `INVALID_PHOTO_TYPE` (other type, empty, or the first bytes are not that image), 400 `BAD_REQUEST` (no `file` part), 400 `INVALID_PARAMETER`, 401, 404 `INCIDENT_NOT_FOUND` (also someone else's), 409 `INVALID_INCIDENT_STATUS_TRANSITION` (`RESOLVED` or `REJECTED`), 413 `PHOTO_TOO_LARGE` |
| `GET /api/v1/incidents/{id}/photo` | reporter or admin | – | `200` + the image bytes, `Content-Type` `image/jpeg`, `image/png` or `image/webp`, with `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. A closed incident keeps its photo | 400 `INVALID_PARAMETER`, 401, 404 `INCIDENT_NOT_FOUND` (also someone else's), 404 `PHOTO_NOT_FOUND` (no photo yet) |
| `GET /api/v1/admin/incidents?status=OPEN&page=0&size=20` | admin | – | `200` + `PageResponse<IncidentResponse>`, newest first (ties by id); `status` is optional. `page` ≥ 0, `size` 1–100 | 400 `INVALID_PARAMETER` (unknown `status`, bad `page`/`size`), 401, 403 |
| `POST /api/v1/admin/incidents/{id}/resolve` | admin | optional `{"resolutionNotes"}` (max 2000 chars); no body is fine | `200` + `IncidentResponse`. Resolving twice returns it unchanged. The asset is `AVAILABLE` again once no other `OPEN`/`IN_REPAIR` incident is left on it; a `RETIRED` asset stays `RETIRED` | 400 `INVALID_PARAMETER`, 400 `VALIDATION_FAILED`, 401, 403, 404 `INCIDENT_NOT_FOUND`, 409 `INVALID_INCIDENT_STATUS_TRANSITION` (from `REJECTED`) |
| `POST /api/v1/admin/incidents/{id}/start-repair` | admin | – | `200` + `IncidentResponse` with `IN_REPAIR`. Starting twice returns it unchanged. The asset stays `UNDER_MAINTENANCE` | 400 `INVALID_PARAMETER`, 401, 403, 404 `INCIDENT_NOT_FOUND`, 409 `INVALID_INCIDENT_STATUS_TRANSITION` (from `RESOLVED` or `REJECTED`) |
| `POST /api/v1/admin/incidents/{id}/reject` | admin | optional `{"resolutionNotes"}` (why it was rejected, max 2000 chars); no body is fine | `200` + `IncidentResponse` with `REJECTED`. Rejecting twice returns it unchanged. Same asset rule as resolve; bookings the engine already moved stay moved | 400 `INVALID_PARAMETER`, 400 `VALIDATION_FAILED`, 401, 403, 404 `INCIDENT_NOT_FOUND`, 409 `INVALID_INCIDENT_STATUS_TRANSITION` (from `RESOLVED`) |

`description`: required, not blank, max 2000 chars. `severity`: `LOW` · `MEDIUM` · `HIGH` · `CRITICAL`. `status`: `OPEN` → `IN_REPAIR` (start-repair) → `RESOLVED`; `OPEN` or `IN_REPAIR` → `REJECTED`. `RESOLVED` and `REJECTED` are final; `resolvedAt`, `resolvedById` and `resolutionNotes` tell who closed the incident, when and why (for both). Audit log (`entityType` `INCIDENT`): `INCIDENT_REPORTED` (by the student), `INCIDENT_REPAIR_STARTED`, `INCIDENT_RESOLVED`, `INCIDENT_REJECTED` (by the admin), `INCIDENT_PHOTO_ADDED` / `INCIDENT_PHOTO_REPLACED` (by whoever uploaded); a repeated action that changes nothing writes no entry. The reporter comes from the token, never from the body. The photo is a separate multipart upload after the report; the file name sent by the client is ignored (the type is checked from the `Content-Type` and the file's first bytes). `photoKey` is only an id: the frontend shows the image by fetching `GET .../photo` with the token and using a blob URL, because a plain `<img src>` sends no token.

```json
// IncidentResponse
{
  "id": 100, "assetId": 12, "reservationId": null, "reportedById": 1,
  "description": "Display cracked", "severity": "HIGH", "status": "OPEN", "photoKey": null,
  "createdAt": "2026-11-03T14:00:00", "resolvedAt": null, "resolvedById": null, "resolutionNotes": null
}
```

After the report commits, `AssetDamagedEvent(incidentId, assetId, reportedById, occurredAt)` starts Calin's reassignment engine.

---

### Reservation endpoints (Calin)

| Method & path | Access | Request body | Response | Status & errors |
|---|---|---|---|---|
| `POST /api/v1/reservations` | Logged in | `CreateReservationRequest` | `201` + `ReservationResponse` | `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST`, `404 ASSET_NOT_FOUND`, `409 INVALID_TIME_RANGE`, `409 INVALID_DURATION`, `409 SLOT_NOT_ALIGNED`, `409 BOOKING_IN_PAST`, `409 OUTSIDE_OPENING_HOURS`, `409 BOOKING_TOO_FAR_AHEAD`, `409 RESERVATION_LIMIT_REACHED`, `409 ASSET_NOT_BOOKABLE`, `409 RESERVATION_CONFLICT` |
| `GET /api/v1/reservations/me` | Logged in | – | `200` + `PageResponse<ReservationResponse>` | `400 VALIDATION_FAILED`, `400 INVALID_PARAMETER`, `401 UNAUTHORIZED` (accepts optional `status` filter, supporting single or multiple statuses e.g. `status=CONFIRMED&status=CHECKED_OUT`) |
| `GET /api/v1/reservations/me/summary` | Logged in | – | `200` + `ReservationSummaryResponse` | `401 UNAUTHORIZED` |
| `GET /api/v1/reservations/availability?assetId=...&startTime=...&endTime=...` | Logged in | – | `200` + `AvailabilityResponse` | `400 INVALID_PARAMETER`, `401 UNAUTHORIZED`, `404 ASSET_NOT_FOUND`, `409 MISSING_PARAMETER`, `409 CONFLICTING_PARAMETERS`, `409 INVALID_TIME_RANGE`, `409 SLOT_NOT_ALIGNED`, `409 INVALID_DURATION`, `409 BOOKING_IN_PAST`, `409 OUTSIDE_OPENING_HOURS`, `409 BOOKING_TOO_FAR_AHEAD` |
| `GET /api/v1/reservations/{id}` | Owner or Admin | – | `200` + `ReservationResponse` | `404 RESERVATION_NOT_FOUND` (404 on others' IDs) |
| `POST /api/v1/reservations/{id}/cancel` | Owner or Admin | – | `200` + `ReservationResponse` | `404 RESERVATION_NOT_FOUND`, `409 CANCELLATION_NOT_ALLOWED`, `409 INVALID_RESERVATION_STATUS_TRANSITION` |

| `POST /api/v1/reservations/bundle` | Logged in | `CreateMultiAssetReservationRequest` | `201` + `MultiAssetReservationResponse` | `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST`, `404 ASSET_NOT_FOUND`, `409 RESERVATION_CONFLICT`, `409 SLOT_HELD`, `409 ASSET_NOT_BOOKABLE`, `409 RESERVATION_LIMIT_REACHED`, `409 INVALID_TIME_RANGE`, `409 INVALID_DURATION`, `409 SLOT_NOT_ALIGNED`, `409 BOOKING_IN_PAST`, `409 OUTSIDE_OPENING_HOURS`, `409 BOOKING_TOO_FAR_AHEAD` |
| `GET /api/v1/reservations/bundle/{bundleId}` | Owner or Admin | – | `200` + `MultiAssetReservationResponse` | `401 UNAUTHORIZED`, `404 BUNDLE_NOT_FOUND` |
| `POST /api/v1/reservations/bundle/{bundleId}/cancel` | Owner or Admin | optional `?reason=...` | `200` + `List<ReservationResponse>` | `400 VALIDATION_FAILED`, `401 UNAUTHORIZED`, `404 BUNDLE_NOT_FOUND` |

| `POST /api/v1/reservations/{id}/extend` | Owner or Admin | `ExtendReservationRequest` | `200` + `ReservationResponse` | `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST`, `401 UNAUTHORIZED`, `404 RESERVATION_NOT_FOUND`, `409 EXTENSION_CONFLICT`, `409 SLOT_HELD`, `409 ASSET_NOT_BOOKABLE`, `409 CHECKOUT_WINDOW_CLOSED`, `409 RESERVATION_ALREADY_EXPIRED`, `409 INVALID_RESERVATION_STATUS_TRANSITION`, `409 BUNDLE_EXTEND_NOT_ALLOWED`, `409 INVALID_TIME_RANGE`, `409 SLOT_NOT_ALIGNED`, `409 INVALID_DURATION`, `409 OUTSIDE_OPENING_HOURS`, `409 BOOKING_TOO_FAR_AHEAD` |
| `POST /api/v1/reservations/{id}/transfers` | Owner | `InitiateTransferRequest` | `201` + `ReservationTransferResponse` | `400 VALIDATION_FAILED`, `401 UNAUTHORIZED`, `404 RESERVATION_NOT_FOUND`, `404 USER_NOT_FOUND`, `409 CANNOT_TRANSFER_TO_SELF`, `409 TRANSFER_ALREADY_PENDING`, `409 TRANSFER_NOT_ALLOWED`, `409 TRANSFER_LIMIT_REACHED`, `409 BUNDLE_TRANSFER_NOT_ALLOWED` |
| `GET /api/v1/reservations/transfers/pending` | Logged in | – | `200` + `List<ReservationTransferResponse>` | `401 UNAUTHORIZED` |
| `POST /api/v1/reservations/transfers/{transferId}/accept` | Recipient | – | `200` + `ReservationTransferResponse` | `401 UNAUTHORIZED`, `404 TRANSFER_NOT_FOUND`, `409 RESERVATION_LIMIT_REACHED`, `409 TRANSFER_NOT_ALLOWED` |
| `POST /api/v1/reservations/transfers/{transferId}/reject` | Recipient or Admin | – | `200` + `ReservationTransferResponse` | `401 UNAUTHORIZED`, `404 TRANSFER_NOT_FOUND`, `409 INVALID_TRANSFER_STATUS_TRANSITION` |
| `POST /api/v1/reservations/transfers/{transferId}/cancel` | Sender or Admin | – | `200` + `ReservationTransferResponse` | `401 UNAUTHORIZED`, `404 TRANSFER_NOT_FOUND`, `409 INVALID_TRANSFER_STATUS_TRANSITION` |
| `POST /api/v1/reservations/holds` | Logged in | `CreateHoldRequest` | `201` + `ReservationHoldResponse` | `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST`, `404 ASSET_NOT_FOUND`, `409 ASSET_NOT_BOOKABLE`, `409 RESERVATION_CONFLICT`, `409 SLOT_HELD`, `409 ACTIVE_HOLD_LIMIT_REACHED`, `409 HOLD_COOLDOWN_ACTIVE`, `409 RESERVATION_LIMIT_REACHED`, `409 INVALID_TIME_RANGE`, `409 INVALID_DURATION`, `409 SLOT_NOT_ALIGNED`, `409 BOOKING_IN_PAST`, `409 OUTSIDE_OPENING_HOURS`, `409 BOOKING_TOO_FAR_AHEAD` |
| `POST /api/v1/reservations/holds/{id}/confirm` | Owner or Admin | – | `200` + `ReservationResponse` | `404 HOLD_NOT_FOUND`, `409 HOLD_EXPIRED`, `409 INVALID_HOLD_STATUS_TRANSITION`, `409 RESERVATION_LIMIT_REACHED`, `409 ASSET_NOT_BOOKABLE`, `409 RESERVATION_CONFLICT`, `409 INVALID_TIME_RANGE`, `409 INVALID_DURATION`, `409 SLOT_NOT_ALIGNED`, `409 BOOKING_IN_PAST`, `409 OUTSIDE_OPENING_HOURS`, `409 BOOKING_TOO_FAR_AHEAD` |
| `POST /api/v1/reservations/holds/{id}/release` | Owner or Admin | – | `200` + `ReservationHoldResponse` | `404 HOLD_NOT_FOUND`, `409 INVALID_HOLD_STATUS_TRANSITION` |
| `GET /api/v1/reservations/holds/active` | Logged in | – | `200` + `List<ReservationHoldResponse>` | `401 UNAUTHORIZED` |


| `GET /api/v1/admin/reservations` | Admin | – | `200` + `PageResponse<ReservationResponse>` | `400 VALIDATION_FAILED`, `400 INVALID_PARAMETER`, `401 UNAUTHORIZED`, `403 FORBIDDEN` (optional filters `status`, `userId`, `assetId`, `date`, `page`, `size`, and `sortDir=asc\|desc`) |
| `POST /api/v1/admin/reservations/{id}/check-out` | Admin | – | `200` + `ReservationResponse` | `404 RESERVATION_NOT_FOUND`, `409 CHECKOUT_WINDOW_CLOSED`, `409 ASSET_NOT_BOOKABLE`, `409 RESERVATION_CONFLICT`, `409 INVALID_RESERVATION_STATUS_TRANSITION` |
| `POST /api/v1/admin/reservations/{id}/return` | Admin | – | `200` + `ReservationResponse` | `404 RESERVATION_NOT_FOUND`, `409 INVALID_RESERVATION_STATUS_TRANSITION` |
| `POST /api/v1/admin/reservations/jobs/run-lifecycle` | Admin | – | `200` + `LifecycleSweepSummary` | `401 UNAUTHORIZED`, `403 FORBIDDEN` |
| `GET /api/v1/admin/reassignments?page=0&size=20` | Admin | – | `200` + `PageResponse<ReassignmentLogResponse>` | `400 VALIDATION_FAILED`, `400 INVALID_PARAMETER`, `401 UNAUTHORIZED`, `403 FORBIDDEN` |
| `POST /api/v1/admin/reassignments/chain-shift?targetAssetId=1&startTime=...&endTime=...&dryRun=true` | Admin | – | `200` + `ChainShiftPlanResponse` | `400 VALIDATION_FAILED`, `401 UNAUTHORIZED`, `403 FORBIDDEN`, `404 ASSET_NOT_FOUND` (`dryRun` defaults to `true`: the plan is only computed; pass `dryRun=false` to apply it. `startTime` must be before `endTime`, local time without `Z`/offset, else 400) |

```json
// CreateReservationRequest
{
  "assetId": 1,
  "startTime": "2026-11-03T10:00:00",
  "endTime": "2026-11-03T12:00:00"
}


// CreateMultiAssetReservationRequest
{
  "assetIds": [1, 2, 3],
  "startTime": "2026-11-03T14:00:00",
  "endTime": "2026-11-03T16:00:00"
}

// MultiAssetReservationResponse
{
  "bundleId": "b1c2d3e4-f5a6-7890-abcd-ef1234567890",
  "startTime": "2026-11-03T14:00:00",
  "endTime": "2026-11-03T16:00:00",
  "totalAssets": 3,
  "reservations": [ /* list of ReservationResponse */ ]

// ExtendReservationRequest
{
  "newEndTime": "2026-11-03T14:00:00"
// InitiateTransferRequest
{
  "recipientUserId": 2
}

// ReservationTransferResponse
{
  "id": 1,
  "reservationId": 10,
  "fromUserId": 1,
  "toUserId": 2,
  "status": "PENDING",
  "requestedAt": "2026-11-09T10:00:00",
  "respondedAt": null


}

// ReservationResponse (returned by booking, check-out, return, cancel)
{
  "id": 10,
  "userId": 2,
  "assetId": 1,
  "originalAssetId": null,
  "startTime": "2026-11-03T10:00:00",
  "endTime": "2026-11-03T12:00:00",
  "status": "CONFIRMED",
  "createdAt": "2026-10-25T14:30:00",
  "checkedOutAt": null,
  "returnedAt": null,
  "cancelledAt": null,
  "cancelReason": null
}

// LifecycleSweepSummary (returned by POST /api/v1/admin/reservations/jobs/run-lifecycle)
{
  "executedAt": "2026-11-03T10:35:00",
  "noShowReleasedCount": 1,
  "markedOverdueCount": 1,
  "preventivelyReassignedCount": 1,
  "preventivelyReassignedReservationIds": [33]
}

// AvailabilityResponse (returned by GET /api/v1/reservations/availability)
{
  "assetId": 1,
  "categoryId": null,
  "startTime": "2026-11-03T14:00:00",
  "endTime": "2026-11-03T16:00:00",
  "available": true,
  "availableAssetIds": [1],
  "conflictReason": null
}

// ReservationSummaryResponse (returned by GET /api/v1/reservations/me/summary)
{
  "activeCount": 2,
  "completedCount": 5,
  "reassignedCount": 1
}

// ReassignmentLogResponse (returned by GET /api/v1/admin/reassignments)
{
  "id": 1,
  "incidentId": 100,
  "reservationId": 10,
  "fromAssetId": 501,
  "toAssetId": 502,
  "outcome": "REASSIGNED",
  "strategy": "SameModelStrategy",
  "createdAt": "2026-11-03T14:00:00"
}

// ChainShiftPlanResponse (returned by POST /api/v1/admin/reassignments/chain-shift)
{
  "targetAssetId": 1,
  "startTime": "2026-11-15T10:00:00",
  "endTime": "2026-11-15T12:00:00",
  "resolved": true,
  "applied": false,
  "steps": [
    {
      "reservationId": 102,
      "userId": 43,
      "fromAssetId": 2,
      "fromAssetName": "Rigol Scope #2",
      "toAssetId": 3,
      "toAssetName": "Rigol Scope #3",
      "startTime": "2026-11-15T10:00:00",
      "endTime": "2026-11-15T12:00:00",
      "strategyName": "SameModel"
    },
    {
      "reservationId": 101,
      "userId": 42,
      "fromAssetId": 1,
      "fromAssetName": "Rigol Scope #1",
      "toAssetId": 2,
      "toAssetName": "Rigol Scope #2",
      "startTime": "2026-11-15T10:00:00",
      "endTime": "2026-11-15T12:00:00",
      "strategyName": "SameModel"
    }
  ],
  "message": "Found feasible augmenting path of 2 shift(s) freeing 'Rigol Scope #1' for [2026-11-15T10:00:00 - 2026-11-15T12:00:00]."
}

`conflictReason`: `null` when `available` is `true`. When `false`: `RESERVATION_CONFLICT` (asset has overlapping active booking), `ASSET_NOT_BOOKABLE` (asset is `UNDER_MAINTENANCE` or `RETIRED`), or `NO_ASSETS_AVAILABLE` (no free assets remain in the queried category or unknown category).
```

---

### Project endpoints (Matei)

All endpoints below require a valid token (`401 UNAUTHORIZED`) and are open to both `STUDENT` and `ADMIN` (covered by `SecurityConfig`'s `/api/v1/**` rule).

| Method & Path | Access | Response | Errors |
|---|---|---|---|
| `GET /api/v1/projects?q=&lang=` | `STUDENT`, `ADMIN` | `200` `ProjectSearchResponse` | `400 VALIDATION_FAILED`, `401 UNAUTHORIZED` |
| `GET /api/v1/projects/{slug}?lang=&start=&end=` | `STUDENT`, `ADMIN` | `200` `ProjectDetailsResponse` | `400 VALIDATION_FAILED`, `400 INVALID_PARAMETER`, `401 UNAUTHORIZED`, `404 PROJECT_NOT_FOUND` |
| `POST /api/v1/projects/{slug}/reserve` | `STUDENT`, `ADMIN` | `201` `ReserveProjectResponse` | `400 VALIDATION_FAILED`, `400 MALFORMED_REQUEST`, `401 UNAUTHORIZED`, `404 PROJECT_NOT_FOUND`, `409 PROJECT_COMPONENT_UNAVAILABLE`, `409` any `bookBundle` code (`RESERVATION_CONFLICT`, `SLOT_HELD`, `ASSET_NOT_BOOKABLE`, `RESERVATION_LIMIT_REACHED`, `BOOKING_IN_PAST`, `BOOKING_TOO_FAR_AHEAD`, `SLOT_NOT_ALIGNED`, `INVALID_DURATION`, `OUTSIDE_OPENING_HOURS`) |

Query parameters:
- `q` (`GET /api/v1/projects`, optional, max 200 characters): search text matched against both RO and EN keywords/titles (diacritics stripped, prefix matching ≥ 5 characters). Empty or stop-words-only returns all projects sorted by English title (`ambiguous: false`, `score: 0`). Over 200 characters → `400 VALIDATION_FAILED`. Not paginated (~10 projects in the library); no catalog/reservation calls on the list view.
- `lang` (both endpoints, optional, default `en`): `ro` or `en` (case-insensitive). Any other value → `400 VALIDATION_FAILED`.
- `start`, `end` (`GET /api/v1/projects/{slug}`, optional, ISO-8601 local date-time without offset e.g. `2026-11-16T10:00:00`):
  - Without `start`/`end`: `available` counts units of that `model` with status `AVAILABLE` (`CatalogApi.findAvailableByModel(model, null)`); `slotChecked: false`.
  - With both `start` and `end`: `available` counts `AVAILABLE` units that are also free in the slot (`ReservationApi.isAssetFree(id, start, end, null)`); `slotChecked: true`.
  - Providing only one of `start`/`end`, or `end` not after `start` → `400 VALIDATION_FAILED`. Malformed date-time → `400 INVALID_PARAMETER`.
  - `reservable` is `true` when every component has `available >= quantity`.

`POST /api/v1/projects/{slug}/reserve` ("Reserve all"): body `ReserveProjectRequest { start, end }`, both required, ISO-8601 local date-time **without offset** (`Z` or `+02:00` → `400 MALFORMED_REQUEST`, same as the reservation requests since #233).
1. Unknown slug → `404 PROJECT_NOT_FOUND`; `end` not after `start` → `400 VALIDATION_FAILED`.
2. For each component, in file order: the `AVAILABLE` units of its `model`, lowest id first, that are free in the slot (`ReservationApi.isAssetFree`); the first `quantity` are picked. Fewer → `409 PROJECT_COMPONENT_UNAVAILABLE`, the `detail` names the model (also when the catalog has no unit of that model yet).
3. All picked units are booked in one call to Calin's `ReservationApi.bookBundle(currentUser, ids, start, end)`: all or nothing, one `bundleId`. It locks the assets and checks again, so its errors come back unchanged (`RESERVATION_CONFLICT` / `SLOT_HELD` if a unit was taken in the meantime, booking rules, `RESERVATION_LIMIT_REACHED` for students; admins skip the limit). No automatic retry: check availability again and retry.
4. The booking user is always the logged-in user (JWT), never a body field. The new bookings show in `GET /api/v1/reservations/me` with the same `bundleId`.

```json
// ProjectSearchResponse (returned by GET /api/v1/projects?q=termometru&lang=ro)
{
  "ambiguous": false,
  "items": [
    {
      "slug": "thermometer",
      "title": "Termometru digital",
      "summary": "Masoara temperatura ambientala cu un senzor DS18B20 si afiseaza valoarea pe un ecran LCD.",
      "difficulty": "BEGINNER",
      "estimatedHours": 3,
      "componentCount": 4,
      "score": 4
    }
  ]
}

// ProjectDetailsResponse (returned by GET /api/v1/projects/thermometer?lang=en&start=2026-11-16T10:00:00&end=2026-11-16T12:00:00)
{
  "slug": "thermometer",
  "title": "Digital thermometer",
  "summary": "Measure ambient temperature with a DS18B20 sensor and display it on an I2C LCD screen.",
  "difficulty": "BEGINNER",
  "estimatedHours": 3,
  "components": [
    {
      "model": "Arduino Uno R3",
      "quantity": 1,
      "why": "Reads the 1-Wire sensor and drives the I2C display.",
      "available": 2
    }
  ],
  "bringYourself": [
    "USB-B cable",
    "4.7 kOhm resistor"
  ],
  "steps": [
    {
      "title": "Wire the DS18B20 sensor",
      "text": "Connect VDD to 5V, GND to GND, and DQ to digital pin D2 with a 4.7 kOhm pull-up resistor to 5V."
    }
  ],
  "mistakes": [
    "Forgetting the 4.7 kOhm pull-up resistor on the data line (reads -127 C)."
  ],
  "slotChecked": true,
  "reservable": true
}

// ReserveProjectRequest (POST /api/v1/projects/oscilloscope-pwm/reserve)
{ "start": "2026-11-10T10:00:00", "end": "2026-11-10T12:00:00" }

// ReserveProjectResponse (201)
{
  "bundleId": "660ebcaa-aa01-473d-b769-4d22d1a6913e",
  "start": "2026-11-10T10:00:00",
  "end": "2026-11-10T12:00:00",
  "items": [
    { "assetId": 10, "assetName": "STM32F4-Discovery Kit 1", "model": "STM32F407G-DISC1" },
    { "assetId": 2, "assetName": "Rigol DS1054Z Digital Oscilloscope 2", "model": "DS1054Z" }
  ]
}

// 409 when a model is short in the slot
{ "status": 409, "code": "PROJECT_COMPONENT_UNAVAILABLE", "detail": "Not enough free units of 'DS1054Z' in this slot: need 1, found 0" }
```

---

### Pickup pass (Matei)

A 6-digit code that changes every 30 seconds proves at the lab desk that the person owns the account (like a bank's authenticator app). Nothing is stored: the code is `HMAC-SHA256(app.jwt.secret, "pickup-v1|userId|timeStep")` cut to 6 digits. Verification is a separate step; check-out and return (Calin) do not require it.

| Method & Path | Access | Request | Response | Errors |
|---|---|---|---|---|
| `GET /api/v1/users/me/pickup-pass` | any logged-in user | – | `200` `PickupPassResponse` | `401 UNAUTHORIZED` |
| `POST /api/v1/admin/pickup-pass/verify` | `ADMIN` | `VerifyPickupPassRequest` | `200` `PickupPassVerifiedResponse` + audit row `PICKUP_PASS_VERIFIED` (actor = the admin, entity `USER`) | `400 VALIDATION_FAILED` (no `userId`, or `code` not exactly 6 digits), `401 UNAUTHORIZED`, `403 FORBIDDEN`, `409 PICKUP_CODE_INVALID` |

`GET` response. `code` is a JSON **string** (leading zeros); `validForSeconds` counts down from 30 to 1:

```json
{ "code": "493027", "qrText": "campus-pickup:1:12:493027", "validForSeconds": 17 }
```

`POST` request and response. The current and the previous 30-second step are accepted, so a code is good for at most 60 seconds:

```json
{ "userId": 12, "code": "493027" }
```
```json
{ "userId": 12, "fullName": "Ana Pop", "email": "ana@campus.test" }
```

`409 PICKUP_CODE_INVALID` ("The code is wrong or has expired") is the one answer for a wrong code, an old code, an unknown user and a disabled user, so the endpoint does not reveal which accounts exist.

### Catalog endpoints — Categories & Lab Rooms (Liviu)

All endpoints below require a valid token (`401 UNAUTHORIZED`); `/api/v1/admin/**` endpoints require `ADMIN` (`403 FORBIDDEN` for `STUDENT`).

| Method & Path | Access | Response | Errors |
|---|---|---|---|
| `GET /api/v1/categories` | `STUDENT`, `ADMIN` | `200` `CategoryResponse[]` (sorted by `name`) | — |
| `POST /api/v1/admin/categories` | `ADMIN` | `201` `CategoryResponse` | `400 VALIDATION_FAILED`, `409 CATEGORY_NAME_TAKEN` |
| `PUT /api/v1/admin/categories/{id}` | `ADMIN` | `200` `CategoryResponse` | `400 VALIDATION_FAILED`, `404 CATEGORY_NOT_FOUND`, `409 CATEGORY_NAME_TAKEN` |
| `DELETE /api/v1/admin/categories/{id}` | `ADMIN` | `204` | `404 CATEGORY_NOT_FOUND`, `409 CATEGORY_IN_USE` |
| `GET /api/v1/lab-rooms` | `STUDENT`, `ADMIN` | `200` `LabRoomResponse[]` (sorted by `code`) | — |
| `POST /api/v1/admin/lab-rooms` | `ADMIN` | `201` `LabRoomResponse` | `400 VALIDATION_FAILED`, `409 LAB_ROOM_CODE_TAKEN` |
| `PUT /api/v1/admin/lab-rooms/{id}` | `ADMIN` | `200` `LabRoomResponse` | `400 VALIDATION_FAILED`, `404 LAB_ROOM_NOT_FOUND`, `409 LAB_ROOM_CODE_TAKEN` |
| `DELETE /api/v1/admin/lab-rooms/{id}` | `ADMIN` | `204` | `404 LAB_ROOM_NOT_FOUND`, `409 LAB_ROOM_IN_USE` |

```json
// CategoryRequest / CategoryResponse
{ "id": 1, "name": "Oscilloscopes", "description": "Signal measurement equipment" }

// LabRoomRequest / LabRoomResponse (code is normalized to uppercase)
{ "id": 1, "code": "EC105", "name": "Electronics Lab", "building": "EC" }
```

---

### Catalog endpoints — Assets Search, Details & Admin Inventory (Liviu)

All endpoints below require a valid token (`401 UNAUTHORIZED`); `/api/v1/admin/**` endpoints require `ADMIN` (`403 FORBIDDEN` for `STUDENT`).

| Method & Path | Access | Response | Errors |
|---|---|---|---|
| `GET /api/v1/assets` | `STUDENT`, `ADMIN` | `200` `PageResponse<AssetResponse>` | `400 INVALID_PARAMETER` |
| `GET /api/v1/assets/{id}` | `STUDENT`, `ADMIN` | `200` `AssetResponse` | `404 ASSET_NOT_FOUND` |
| `POST /api/v1/admin/assets` | `ADMIN` | `201` `AssetResponse` | `400 VALIDATION_FAILED`, `404 CATEGORY_NOT_FOUND` / `LAB_ROOM_NOT_FOUND`, `409 SERIAL_NUMBER_TAKEN` |
| `PUT /api/v1/admin/assets/{id}` | `ADMIN` | `200` `AssetResponse` | `400 VALIDATION_FAILED`, `404 ASSET_NOT_FOUND` / `CATEGORY_NOT_FOUND` / `LAB_ROOM_NOT_FOUND`, `409 ASSET_RETIRED` |
| `POST /api/v1/admin/assets/{id}/retire` | `ADMIN` | `200` `AssetResponse` (`status: "RETIRED"`) | `404 ASSET_NOT_FOUND`, `409 ASSET_RETIRED` |

`GET /api/v1/assets` optional query parameters:
- `search`: case-insensitive substring match across `name`, `model`, and `serialNumber`
- `categoryId`: numeric category ID
- `labRoomId`: numeric lab room ID
- `status`: `AVAILABLE | UNDER_MAINTENANCE | RETIRED` (retired items are hidden from `STUDENT` searches; `ADMIN` sees all statuses)
- `page` (default `0`, `>= 0`), `size` (default `20`, `1..100`), `sort` (default `name,asc`; whitelisted fields: `name`, `model`, `serialNumber`, `status`, `createdAt`, `updatedAt`, direction `asc` or `desc`)

```json
// CreateAssetRequest
{
  "name": "Rigol DS1054Z no. 1",
  "model": "Rigol DS1054Z",
  "serialNumber": "DS1ZA2419003",
  "description": "4-channel digital oscilloscope",
  "categoryId": 2,
  "labRoomId": 1
}

// AssetResponse
{
  "id": 7,
  "name": "Rigol DS1054Z no. 1",
  "model": "Rigol DS1054Z",
  "serialNumber": "DS1ZA2419003",
  "description": "4-channel digital oscilloscope",
  "status": "AVAILABLE",
  "categoryId": 2,
  "categoryName": "Oscilloscopes",
  "labRoomId": 1,
  "labRoomCode": "EC105",
  "labRoomName": "Electronics Lab",
  "createdAt": "2026-10-29T12:00:00",
  "updatedAt": "2026-10-29T12:00:00"
}
```

---

### Audit log endpoints (Liviu)

All endpoints below require a valid token (`401 UNAUTHORIZED`); `/api/v1/admin/**` endpoints require `ADMIN` (`403 FORBIDDEN` for `STUDENT`).

| Method & Path | Access | Response | Errors |
|---|---|---|---|
| `GET /api/v1/admin/audit-logs` (alias: `GET /api/v1/admin/audit-log`) | `ADMIN` | `200` `PageResponse<AuditLogResponse>` | `400 INVALID_PARAMETER`, `401`, `403` |

`GET /api/v1/admin/audit-logs` optional query parameters:
- `actorId`: ID of the user who performed the action (numeric; `null` for automated system background jobs)
- `action`: machine-readable action code, e.g. `ASSET_RETIRED`, `USER_DISABLED` (case-insensitive)
- `entityType`: target domain type, e.g. `ASSET`, `USER`, `INCIDENT`, `RESERVATION` (case-insensitive)
- `entityId`: ID of the affected entity (numeric)
- `from`: start timestamp filter (ISO-8601 local time without offset, e.g. `2026-11-01T00:00:00`, inclusive; timezone offsets like `Z` or `+05:00` rejected with `400 INVALID_PARAMETER`)
- `to`: end timestamp filter (ISO-8601 local time without offset, e.g. `2026-11-08T23:59:59`, inclusive; timezone offsets rejected with `400 INVALID_PARAMETER`; rejected if `from > to`)
- `page`: zero-based page index (default `0`, `>= 0`)
- `size`: page size (default `20`, `1..100`)
- `sort`: sort property and direction (default `createdAt,desc`; whitelisted fields: `createdAt`, `id`, `action`, `entityType`, `actorId`, `entityId`, direction `asc` or `desc`; deterministic secondary tie-breaking on `id`)

```json
// AuditLogResponse
{
  "id": 1,
  "actorId": 3,
  "action": "ASSET_RETIRED",
  "entityType": "ASSET",
  "entityId": 12,
  "details": "Retired damaged multimeter",
  "createdAt": "2026-11-08T10:00:00"
}
```

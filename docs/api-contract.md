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

Checked top to bottom in `SecurityConfig`; the **first match wins**.

| # | Path | Access |
|---|---|---|
| 1 | `/api/v1/auth/**` (ping, register, login) | public |
| 2 | `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/error` | public |
| 3 | `/api/v1/admin/**` | role `ADMIN` |
| 4 | `/api/v1/**` | any logged-in user (`STUDENT` or `ADMIN`) |
| 5 | anything else | denied |

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
| `GET /api/v1/users/me` | logged in | – | `200` + `UserResponse` |

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

### Token claims

| Claim | Example | Used by |
|---|---|---|
| `sub` | `"1"` (user id as a string) | `CurrentUser.id()` (Stefania) |
| `email` | `"student1@campus.test"` | display only |
| `roles` | `["STUDENT"]` or `["ADMIN"]` | Spring turns it into `ROLE_STUDENT` / `ROLE_ADMIN` (`hasRole("ADMIN")`) |
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
| `NotFoundException(code, msg)` | 404 |
| `ConflictException(code, msg)` | 409 |
| `BusinessRuleException(code, msg)` | 409 |
| `InvalidStateTransitionException(code, msg)` | 409 |
| `ForbiddenException(code, msg)` | 403 |

The frontend shows a message per `code` (fall back to `detail`). Never put stack traces, SQL or class names in `detail`.

---

## 5. Error codes

### Generic (Liviu's handler + Matei's security)

| Code | Status | When |
|---|---|---|
| `VALIDATION_FAILED` | 400 | `@Valid` failed; see `errors` |
| `MALFORMED_REQUEST` | 400 | unreadable JSON, wrong date format |
| `INVALID_PARAMETER` | 400 | bad query parameter, e.g. an unknown `sort` field |
| `UNAUTHORIZED` | 401 | no token, invalid or expired token |
| `FORBIDDEN` | 403 | logged in, but not allowed (e.g. a student on `/api/v1/admin/**`) |
| `STALE_VERSION` | 409 | someone else changed the row first (optimistic locking): reload and retry |
| `PHOTO_TOO_LARGE` | 413 | upload over 5 MB |
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

`ASSET_NOT_FOUND` 404 · `CATEGORY_NOT_FOUND` 404 · `LAB_ROOM_NOT_FOUND` 404 · `SERIAL_NUMBER_TAKEN` 409 · `CATEGORY_NAME_TAKEN` 409 · `CATEGORY_IN_USE` 409 · `LAB_ROOM_IN_USE` 409 · `ASSET_RETIRED` 409 · `INVALID_ASSET_STATUS_TRANSITION` 409

### Reservations (Calin)

`RESERVATION_NOT_FOUND` 404 · `RESERVATION_CONFLICT` 409 · `ASSET_NOT_BOOKABLE` 409 · `RESERVATION_LIMIT_REACHED` 409 · `BOOKING_TOO_FAR_AHEAD` 409 · `BOOKING_IN_PAST` 409 · `INVALID_TIME_RANGE` 400 · `INVALID_DURATION` 400 · `OUTSIDE_OPENING_HOURS` 409 · `SLOT_NOT_ALIGNED` 400 · `CANCELLATION_NOT_ALLOWED` 409 · `CHECKOUT_WINDOW_CLOSED` 409 · `INVALID_RESERVATION_STATUS_TRANSITION` 409

### Incidents (Stefania)

`INCIDENT_NOT_FOUND` 404 · `INVALID_PHOTO_TYPE` 400 · `INVALID_INCIDENT_STATUS_TRANSITION` 409 (+ `ASSET_NOT_FOUND`, `ASSET_RETIRED` and `PHOTO_TOO_LARGE` above)

Owners: check the status codes above when you implement them, and fix this list if yours differ.

---

## 6. Endpoints per module

Each owner adds their endpoints here (method + path, access, request/response JSON example, error codes) in the same PR as the code. Drafts are in each member guide §4.

| Module | Owner | Base paths |
|---|---|---|
| Auth / users | Matei (auth), Stefania (admin) | `/api/v1/auth/**`, `/api/v1/users/me`, `/api/v1/admin/users/**` |
| Catalog | Liviu | `/api/v1/assets`, `/api/v1/categories`, `/api/v1/lab-rooms`, `/api/v1/admin/assets`, `/api/v1/admin/categories`, `/api/v1/admin/lab-rooms`, `/api/v1/admin/audit-log` |
| Reservations | Calin | `/api/v1/reservations/**`, `/api/v1/admin/reservations/**`, `/api/v1/admin/reassignments` |
| Incidents | Stefania | `/api/v1/incidents/**`, `/api/v1/admin/incidents/**` |


### Incident endpoints (Stefania)

| Method & path | Access | Request | Response | Errors |
|---|---|---|---|---|
| `POST /api/v1/incidents` | logged in | `{"assetId","description","severity"}` | `201` + `IncidentResponse`. The asset becomes `UNDER_MAINTENANCE` | 400 `VALIDATION_FAILED`, 401, 404 `ASSET_NOT_FOUND`, 409 `ASSET_RETIRED` |
| `GET /api/v1/admin/incidents?status=OPEN&page=0&size=20` | admin | – | `200` + `PageResponse<IncidentResponse>`, newest first; `status` is optional | 400 `INVALID_PARAMETER`, 401, 403 |
| `POST /api/v1/admin/incidents/{id}/resolve` | admin | optional `{"resolutionNotes"}` (max 2000 chars); no body is fine | `200` + `IncidentResponse`. Resolving twice returns it unchanged. The asset is `AVAILABLE` again once no other `OPEN`/`IN_REPAIR` incident is left on it | 400 `VALIDATION_FAILED`, 401, 403, 404 `INCIDENT_NOT_FOUND`, 409 `INVALID_INCIDENT_STATUS_TRANSITION` (from `REJECTED`) |

`description`: required, not blank, max 2000 chars. `severity`: `LOW` · `MEDIUM` · `HIGH` · `CRITICAL`. `status`: `OPEN` · `IN_REPAIR` · `RESOLVED` · `REJECTED`. The reporter comes from the token, never from the body. The photo comes later as a separate multipart upload.

```json
// IncidentResponse
{
  "id": 100, "assetId": 12, "reservationId": null, "reportedById": 1,
  "description": "Display cracked", "severity": "HIGH", "status": "OPEN", "photoKey": null,
  "createdAt": "2026-11-03T14:00:00", "resolvedAt": null, "resolvedById": null, "resolutionNotes": null
}
```

After the report commits, `AssetDamagedEvent(incidentId, assetId, reportedById, occurredAt)` starts Calin's reassignment engine.

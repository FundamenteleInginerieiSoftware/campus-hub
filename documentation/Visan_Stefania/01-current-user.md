# Who Is Logged In: `CurrentUser` (Vișan Ștefania)

**Role:** Member 4
**Date:** 2026-10-30
**Package:** `com.campus.common.security`
**PR:** #74 (`feat(security): add CurrentUser as an injectable bean`)

---

## Part 1: In simple terms

When a student books an oscilloscope or reports a broken camera, the backend has to know **who** is asking. After login, the browser sends a token (a signed "ticket") with every request. The token says "this is user 42, a student".

`CurrentUser` is a small helper that every module asks: "who is the user of this request, and is it an admin?"

The important rule: **the user id always comes from the token, never from the request body.** If a student sent `"reportedById": 7` in the body, someone else's id, the backend ignores it. Otherwise any student could act in someone else's name.

If nobody is logged in, the helper answers **401 Unauthorized** (a clear "log in first") instead of crashing with a 500.

---

## Part 2: Technical details

### Files

| File | What it is |
|---|---|
| `backend/src/main/java/com/campus/common/security/CurrentUser.java` | Spring `@Component`, injected into controllers |
| `backend/src/test/java/com/campus/common/security/CurrentUserTest.java` | 4 unit tests |

### Methods

| Method | Returns | How |
|---|---|---|
| `Long id()` | the logged-in user's id | `Long.valueOf(jwt.getSubject())`: login puts the user id in the JWT `sub` claim |
| `boolean isAdmin()` | `true` for admins | looks for the `ROLE_ADMIN` authority, which `SecurityConfig` builds from the token's `roles: ["ADMIN"]` |
| `Jwt jwt()` (private) | the verified token | reads `SecurityContextHolder`; if there is no JWT principal, throws `UnauthorizedException` → 401 |

### Design choices

| Choice | Why |
|---|---|
| A bean, not a static helper | Controllers get it by constructor injection; in `@WebMvcTest` slices it is replaced with `@MockitoBean`, so tests choose the user |
| In `common.security`, not in `user` | Every module needs it; `common` is the only package all modules may depend on (ArchUnit module rules) |
| `isAdmin()` returns `false` with no login | Safe default on public paths: never a `NullPointerException` |
| 401 on a public path | Only `/api/v1/auth/**` is public; reaching `id()` there is a programming mistake, and 401 is a clear answer instead of 500 |
| Bad `sub` (not a number) | Rejected earlier by `SecurityConfig` with 401 (Matei, #176), so `id()` never sees it |

### Where it is used

- `IncidentController`: the reporter of a new incident; "my incidents"; who may see one incident.
- `AdminIncidentController`: the admin who resolves or rejects an incident.
- Liviu's catalog services (`AssetService`, `CategoryService`, `LabRoomService`): the admin recorded in the audit log.
- Calin's `ReservationService`: the owner of a booking.
- `AuthService` (Matei) writes the token so that `CurrentUser` can read it: the user id in `sub`, the role in `roles`.

### Tests (`CurrentUserTest`)

| Test | Checks |
|---|---|
| `readsIdAndAdminRoleFromJwt` | `sub = "7"` + `ROLE_ADMIN` → `id() == 7`, `isAdmin() == true` |
| `studentIsNotAdmin` | `sub = "3"` + `ROLE_STUDENT` → `id() == 3`, `isAdmin() == false` |
| `noLoginIsNotAdmin` | empty security context → `false`, no exception |
| `noTokenGives401` | empty security context → `id()` throws `UnauthorizedException` with code `UNAUTHORIZED` |

### Defense questions

- **Why not take the user id from the body?** The body is controlled by the client; the token is signed by the server. Trusting the body would let a student book or report as someone else (IDOR). Matei's `SecurityPassIT.userIdInTheBodyIsIgnored` checks this end to end.
- **Where is the token verified?** In `SecurityConfig` (Spring's OAuth2 resource server). `CurrentUser` only reads a token that is already verified.

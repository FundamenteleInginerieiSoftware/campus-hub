# "Who am I?" endpoint: GET /api/v1/users/me (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-31
**Branch / PR:** `feat/user-me`
**See also:** `13-auth-register-login.md` (the token this endpoint reads), `11-user-service.md` (`UserService`), `AGENTS.md` §5 (ownership comes from the JWT)

---

## Part 1: In simple terms

After logging in, the website keeps a "ticket" (the token). When the page is reloaded, it still has the ticket but has forgotten whose it is.

1. **What was built:** a new address, `/api/v1/users/me`. The website shows the ticket, and the app answers with that person's name, e-mail and role (student or admin).
2. **Why it is safe:** the address takes no user number at all. The app reads who you are from the ticket itself, so nobody can ask for someone else's account.
3. **Without a ticket**, the app answers "log in first" (401). If the ticket is still valid but the account is gone (for example after a database reset), it answers "no such user" (404).

**Result:** the frontend can show "Logged in as Student One" and hide the admin pages from students, using one call.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/web/UserController.java` | **New.** `@RestController`, `GET /api/v1/users/me` → `userService.me()` |
| `user/service/UserService.java` | New method `me()`; injects `CurrentUser` |
| `user/service/UserServiceTest.java` | +2 tests: `meReturnsTheTokensUser`, `meThrowsUserNotFoundWhenTheAccountIsGone` |
| `user/web/AuthFlowIT.java` | +1 test: `meReturnsTheAccountOfTheToken` (real register + login + token) |
| `docs/api-contract.md` | §3 row for `/users/me`: 401 and 404 cases |
| `documentation/Necula_Matei/02-files-explained.md` | Rows for `UserController`, `me()` and the new tests |

### Key code and why

```java
@Transactional(readOnly = true)
public UserResponse me() {
    Long id = currentUser.id();
    return userRepository.findById(id).map(UserResponse::from)
            .orElseThrow(() -> new NotFoundException("USER_NOT_FOUND", "User " + id + " not found"));
}
```

| Choice | Why |
|---|---|
| The id comes from `CurrentUser`, in the service | AGENTS.md §5: ownership is decided in the service layer, from the JWT, never from the request. The controller has no parameters, so there is nothing to fake. |
| Read from the database, not only from the token | The token has `sub`, `email` and `roles`, but not `fullName`. The database also has the latest data if an admin changes the account later (Stefania's user admin). |
| `UserResponse`, not `UserSummary` | `UserResponse` is the web shape the contract defines for register/login, so the frontend gets the same JSON everywhere. `UserSummary` is for other modules (`UserApi`). |
| `me()` is not on `UserApi` | Other modules never need "the current user's account"; they inject `CurrentUser` for the id and `UserApi` for lookups. Keeping the facade small keeps the contract stable. |
| `readOnly = true`, mapping inside the method | `open-in-view` is off: the `User` entity must be turned into a record before the transaction ends. |
| 404 when the account is gone | A token stays valid for 8 h. After a database reset, an old token can point to an id that no longer exists; 404 `USER_NOT_FOUND` (already in the contract) is clearer than a 500. |
| No extra security rule | `/api/v1/**` already requires a valid token in `SecurityConfig`, so a missing token is 401 before the controller runs. |

### How it connects

- **Stefania (frontend):** after a reload, call `GET /api/v1/users/me` with the stored token. 200 → show the user and pick the menu by `role`; 401 → clear the token and go to the login page (contract §3).
- **Stefania (`CurrentUser`, #74):** this is the first service that injects the bean; it is mocked in the unit test the same way Calin and Stefania do.

### Tests

| Check | Result |
|---|---|
| `UserServiceTest` (Mockito, `CurrentUser` mocked) | found → `UserResponse(1, "student1@campus.test", "Student One", STUDENT)`; unknown id → `USER_NOT_FOUND` |
| `AuthFlowIT` (whole app, `campus_test`) | register → login → `/users/me` with the token: 200, own e-mail, `STUDENT`, no `passwordHash`; without a token: 401 `UNAUTHORIZED` |
| `./gradlew spotlessApply build` | BUILD SUCCESSFUL, 190 tests (187 on `main` + 3) |

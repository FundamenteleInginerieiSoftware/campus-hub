# UserService: the real user lookup (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-26
**Branch / PR:** `feat/user-service`
**See also:** `07-user-api.md` (the contract), `03-user-entity.md` (the users table)

---

## Part 1: In simple terms

`07-user-api.md` set up the "front desk" other parts of the app use to ask about users, but nobody was sitting behind it yet. This PR puts someone there.

1. **"Who is user 5?" now gets a real answer:** id, e-mail, name and role, read from the database. Never the password.
2. **An unknown user gets a clear "not found"** with the code `USER_NOT_FOUND`, in the same error format as the rest of the app.
3. **"Does user 5 exist?" now works**, without loading the whole user.
4. **Tested all three cases.**

**Result:** Calin's bookings, Stefania's incident reports and the notifications can look up real users now, instead of only mocking the lookup in their tests.

---

## Part 2: Technical details

### Files

| File | What it is |
|---|---|
| `user/service/UserService.java` | New: `@Service` that implements `UserApi` |
| `test/.../user/service/UserServiceTest.java` | New: 3 Mockito unit tests |

### Key choices

| Choice | Why |
|---|---|
| `UserService implements UserApi`, `@Service` | Teammates inject the interface (`UserApi`); Spring finds this class as its only implementation. They never see `User` or `UserRepository` |
| `@Transactional(readOnly = true)` on both public methods | Team rule: reads are read-only transactions, on public service methods only. `open-in-view` is off, so the entity is turned into a `UserSummary` **inside** the method |
| `NotFoundException("USER_NOT_FOUND", …)` | Liviu's shared exception (#22). His `GlobalExceptionHandler` turns it into `404` + `code` when it reaches a controller; other modules can also catch it |
| `toSummary` copies 4 fields by hand | No MapStruct (team rule). Leaves out `passwordHash` and `enabled` on purpose |
| `exists` = `existsById` | A `SELECT` of one row's existence, cheaper than loading the user |

### How it connects to teammates

| Who | Uses |
|---|---|
| Calin | `exists(userId)` / `getById` for reservation owners; e-mail for reassignment notifications |
| Stefania | The reporter of an incident |
| Liviu / Matei | The e-mail address for `NotificationService` (log in dev, SES in prod) |

Their unit tests keep mocking `UserApi`; their `@SpringBootTest` tests now get this real bean.

### Testing

| Command / test | Result |
|---|---|
| `cd backend && ./gradlew spotlessApply build` | BUILD SUCCESSFUL, **32 tests** (+3) |
| `getByIdReturnsSummaryWithoutPassword` | A user saved as `Student1@Campus.test` comes back as `UserSummary(5, "student1@campus.test", "Student One", STUDENT)` |
| `getByIdThrowsUserNotFoundForUnknownId` | Unknown id → `NotFoundException` with `code = USER_NOT_FOUND` |
| `existsAsksTheRepository` | `true` / `false` exactly as the repository says |

`UserServiceTest` is plain Mockito, no Spring context: the repository is a mock, so it runs in milliseconds. The id is set with `ReflectionTestUtils.setField`, because in real use only PostgreSQL sets it on insert and `User` has no setter.

### Next

| Step | Waits for |
|---|---|
| `AuthService` + `POST /register`, `POST /login`, `GET /users/me` | Calin's `Clock` on `main` (#27 or #34) |
| `UserSeeder` (dev accounts) | Same |

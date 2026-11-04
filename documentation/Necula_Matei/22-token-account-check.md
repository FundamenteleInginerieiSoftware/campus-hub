# Blocked accounts lose access at once (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-04
**Branch / PR:** `fix/user-token-active-check` (issue #117)
**See also:** `04-password-jwt-roles.md` (tokens and roles), `11-user-service.md` (`UserApi`)

---

## Part 1: In simple terms

1. **When you log in, you get a pass (token) valid for 8 hours.** It says who you are and whether you are a student or an admin.
2. **The problem:** the server trusted the pass alone. If an admin blocked an account, or took away someone's admin rights, the old pass kept working for up to 8 hours.
3. **The fix:** on every request the server now also checks the account in the database. Account gone or blocked → "log in first". The role is read from the database too, so a removed admin right counts immediately.
4. **The cost** is one very fast database lookup per request (by id), which is fine for our size.

**Result:** blocking a user or changing a role takes effect on the user's very next click.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/api/UserApi.java` | New `Optional<Role> findActiveRole(Long id)` |
| `user/service/UserService.java` | `findById(id).filter(User::isEnabled).map(User::getRole)` |
| `config/SecurityConfig.java` | `rolesFromToken()` replaced by `activeUserFromToken(ObjectProvider<UserApi>)` |
| `test/.../config/SecurityConfigTest.java` | `@Transactional`; `bearer(role)` now saves a real user; 3 new tests (disabled, deleted, demoted) |
| `test/.../user/service/UserServiceTest.java` | 2 tests for `findActiveRole` |
| `docs/api-contract.md` §3 | Disabled-after-login row; `roles` claim is now information only |

### The converter

```java
Role role = users.getObject()
        .findActiveRole(Long.valueOf(jwt.getSubject()))
        .orElseThrow(() -> new InvalidBearerTokenException("The account is disabled or no longer exists"));
return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())), jwt.getSubject());
```

| Choice | Why |
|---|---|
| Check in the JWT converter | It runs once per request, right after the signature and expiry checks. An `AuthenticationException` from it becomes our normal 401 JSON |
| Role from the database, not the `roles` claim | A demoted admin must lose admin rights at once. The claim stays in the token (contract §3) |
| New `UserApi` method instead of importing `UserRepository` | `config` only uses other modules through their `api` (AGENTS.md §3). `UserSummary` stays unchanged, so no teammate code changes |
| `ObjectProvider<UserApi>` | Liviu's `@WebMvcTest` slices `@Import(SecurityConfig.class)` without a `UserApi` bean; they log in with `jwt()`, which skips the converter. `ObjectProvider` looks the bean up only when a real token arrives, so their tests keep working |
| `CurrentUser` unchanged | It still reads `sub` from the `Jwt` principal and `ROLE_ADMIN` from the authorities, which now come from the database |

### Effect on others

| Who | Effect |
|---|---|
| Stefania (user admin, W5+) | Her disable / role-change endpoints now work immediately, no extra code |
| Liviu (#120) | Tokens of deleted users are refused, so his audit insert can't fail on the `actor_id` foreign key for that reason |
| Everyone's tests | Only tests that send real signed tokens are affected; those are mine (`SecurityConfigTest`, `AuthFlowIT`). Teammates use `jwt()` / `@WithMockUser` |
| AGENTS.md §9 | The `UserApi` contract line gets `findActiveRole` in a separate docs PR |

### Tests

| Check | Result |
|---|---|
| The 3 new `SecurityConfigTest` tests **without** the converter change | 3 failed |
| `./gradlew spotlessApply build --rerun` with the fix | BUILD SUCCESSFUL, 353 tests |

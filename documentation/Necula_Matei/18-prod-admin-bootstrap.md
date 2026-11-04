# First admin on AWS (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-02
**Branch / PR:** `feat/user-prod-admin-bootstrap`
**See also:** `14-user-seeder.md` (dev accounts), `13-auth-register-login.md` (register only makes students), `09-aws-architecture.md`

---

## Part 1: In simple terms

Sign-up always creates a student, and the 3 test accounts only exist on laptops. So on AWS nobody could ever be an admin. Now the app creates the first admin itself.

1. **Matei sets two secret settings on AWS:** the admin's email (`ADMIN_EMAIL`) and password (`ADMIN_PASSWORD`). They never go into git.
2. **When the app starts on AWS, it creates that admin account** if it doesn't exist yet. The password is stored scrambled, like every other password.
3. **If the account already exists, nothing changes,** so a restart never resets the admin's password and never turns an existing student into an admin.
4. **If the settings are empty, the app starts without an admin** and writes a line in the log saying so.
5. **If the password is too short (under 8) or too long for the scrambler, the app refuses to start** with a clear message, instead of creating an admin nobody can log in with.

**Result:** after the first deploy, the admin can log in on AWS and use every `/api/v1/admin/**` page.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `user/service/AdminBootstrap.java` | New: `@Profile("prod")` `CommandLineRunner` |
| `test/.../user/service/AdminBootstrapTest.java` | New: 4 Mockito tests |
| `02-files-explained.md` | Rows for both files |

`application-prod.yml` already had `app.admin.email: ${ADMIN_EMAIL:}` and `app.admin.password: ${ADMIN_PASSWORD:}` (the `:` means "empty if not set"); until now nothing read them.

### Key choices

| Choice | Why |
|---|---|
| `CommandLineRunner` | Same mechanism as `UserSeeder`: Spring Boot calls it once after startup |
| `@Profile("prod")` | Only AWS runs `prod`. Laptops (`dev`) get the seeded `admin@campus.test`, tests (`test`) get neither |
| No `@Order` | `dev` and `prod` never run together, so it never competes with the dev seeders |
| `@Value` in the constructor | Same style as `AuthService` (`app.jwt.expiration`); two strings don't need a `@ConfigurationProperties` class |
| `existsByEmailIgnoreCase` → skip | Runs on every start (every deploy). Never overwrites: an admin who changed the password keeps it, and an email that someone already registered as a student isn't promoted silently |
| 8 characters to 72 bytes | Same rule as `RegisterRequest`. BCrypt ignores everything after 72 bytes, so a longer password would not really be the password |
| `IllegalStateException` on a bad password | A wrong setting is a deploy mistake: failing at startup shows it in the logs right away |
| `new User(...)` + shared `Clock` | Same path as register: email trimmed + lowercased, BCrypt hash, `created_at` from `LocalDateTime.now(clock)` |
| Log only the email | Passwords never go to logs (CloudWatch keeps logs) |

### How it connects

| Who | How |
|---|---|
| Matei (Terraform, W5) | `ADMIN_EMAIL` / `ADMIN_PASSWORD` become SSM parameters under `/campus/*`, passed to the container like `JWT_SECRET` |
| Liviu, Calin, Stefania | Their `/api/v1/admin/**` endpoints (inventory, reservations desk, incident queue, user admin) need this admin to be usable on AWS |
| Stefania (user admin, W5) | Once this admin exists, more admins can be made through her user admin endpoints instead of new settings |

### Tests

| Test | Checks |
|---|---|
| `createsAdminWithHashedPasswordWhenMissing` | `" Boss@Campus.test "` → one `ADMIN` saved as `boss@campus.test`; the hash is not the password and BCrypt matches it |
| `leavesAnExistingAccountAlone` | email exists → `save` never called |
| `doesNothingWhenEmailOrPasswordIsBlank` | empty email, then blank password → no repository call at all |
| `refusesToStartWithATooShortPassword` | `"short"` → `IllegalStateException` mentioning `ADMIN_PASSWORD`, nothing saved |

`./gradlew spotlessApply build`: **BUILD SUCCESSFUL, 281 tests** (277 on `main` + 4 new).

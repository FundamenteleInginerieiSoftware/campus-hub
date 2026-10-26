# Error codes were missing on wrong input (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-26
**Branch / PR:** `fix/config-error-codes`
**See also:** `05-security-error-json.md` (401/403 JSON), `docs/api-contract.md` §4 (error format)

---

## Part 1: In simple terms

Every error the app sends should carry a short code (like `VALIDATION_FAILED`), so the website knows what to show. For wrong input, the code was missing.

1. **Found it while reviewing Stefania's incident PR (#40):** a report without a severity came back as "Invalid request content." with no code and no list of the fields that were wrong.
2. **The cause was one setting in my `application.yml`.** It switched on Spring Boot's own error handler, and that one answered before Liviu's error handler got the chance.
3. **Removed the setting.** Now Liviu's handler answers every error, with the code and the field list.
4. **Tested it** for a missing field, broken data and a page that doesn't exist.

**Result:** the website gets the same error shape, with a code, for every error. The login form can show "Email is required" next to the right field.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `application.yml` | Removed `spring.mvc.problemdetails.enabled: true` (+ a bug-fix comment) |
| `test/.../config/ErrorJsonTest.java` | New: 3 tests with a test-only endpoint |
| `02-files-explained.md` | Rows for the new test and the removed setting |

### What was wrong

| | Before (setting on) | After (setting off) |
|---|---|---|
| Missing/blank field (`@Valid`) | 400 `"Invalid request content."`, **no `code`, no `errors`** | 400 `VALIDATION_FAILED` + `errors: [{field, message}]` |
| Broken JSON / missing body | 400 `"Failed to read request"`, no `code` | 400 `MALFORMED_REQUEST` |
| Wrong type in a query param (`?status=NOPE`) | 400, no `code` | 400 `INVALID_PARAMETER` |
| Unknown path, wrong method, wrong content type | 404 / 405 / 415, no `code` | same status + `NOT_FOUND` / `METHOD_NOT_ALLOWED` / `UNSUPPORTED_MEDIA_TYPE` |
| Thrown `AppException`s (`NotFoundException`, ...) and unknown `sort` fields | had a `code` | unchanged |

**Why:** `problemdetails.enabled: true` registers Boot's `ProblemDetailsExceptionHandler` (a `@ControllerAdvice` extending `ResponseEntityExceptionHandler`) with a high priority. Spring asks the advice classes in order, and the first one with a matching `@ExceptionHandler` wins. Boot's handler covers all of Spring MVC's own exceptions (`MethodArgumentNotValidException`, `HttpMessageNotReadableException`, `NoResourceFoundException`, ...), so Liviu's `GlobalExceptionHandler` (#22) only ever saw our own exceptions. Without the setting, his handler gets everything: his specific handlers add the codes, and his catch-all keeps the right status for Spring's `ErrorResponse` exceptions (404/405/415 stay 404/405/415, not 500).

### How it connects

- **Liviu:** his handler was already right; it just wasn't reached. His unit tests call the handler methods directly, which is why they didn't catch it.
- **Everyone with a `@Valid` request** (Stefania's incident report, Calin's booking, my register/login): the frontend now gets `VALIDATION_FAILED` with the field list, as promised in `docs/api-contract.md` §4.

### Tests

| Test | Checks |
|---|---|
| `validationErrorHasCodeAndFieldErrors` | blank `name` → 400, `code = VALIDATION_FAILED`, `errors[0].field = name` |
| `unreadableJsonHasCode` | `{not json` → 400, `code = MALFORMED_REQUEST` |
| `unknownPathHasCode` | `/api/v1/does-not-exist` with a token → 404, `code = NOT_FOUND` |

- `main` has no endpoint with a `@Valid` body yet, so the test brings its own: a small `@RestController` nested in the test class and added with `@Import` (Spring's test filter keeps nested test classes out of the normal component scan).
- The token comes from `jwt()` in spring-security-test: it puts a ready-made `Jwt` in the security context, so no real signing is needed.
- Test first: all 3 failed on `main`, then passed after removing the setting.
- `./gradlew spotlessApply build`: **BUILD SUCCESSFUL, 37 tests** (34 before + 3 new).

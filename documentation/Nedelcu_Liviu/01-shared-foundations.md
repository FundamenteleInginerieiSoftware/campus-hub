# Shared Foundations: Errors, Pagination & Service Interfaces (Nedelcu Liviu)

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)
**Date:** 2026-10-25
**Branch / PR:** `feat/common-foundations` (#22)

---

## Part 1: In simple terms

Before each module could build its own screens and business rules, the whole backend needed one common language for three everyday tasks:

1. **Clear, uniform error messages:** Whether a student tries to book a busy oscilloscope or forgets to fill in a form field, the backend always answers in the same JSON shape (with a machine-readable `code` like `ASSET_NOT_FOUND` or `VALIDATION_FAILED`), never leaking Java stack traces or database internals.
2. **Consistent page-by-page lists:** Every long list (equipment, reservations, incidents, audit entries) returns the same 5-field page wrapper (`content`, `page`, `size`, `totalElements`, `totalPages`).
3. **Pluggable infrastructure interfaces:** Modules can record audit entries, store incident photos, and notify students today using local/dev implementations, while Matei can plug in AWS S3 and SES for production without changing a single line in the domain modules.

---

## Part 2: Technical details

### Files

| Area | Files | Purpose |
|---|---|---|
| `common/error/` | `AppException`, `NotFoundException` (404), `ConflictException` (409), `BusinessRuleException` (409), `InvalidStateTransitionException` (409), `ForbiddenException` (403), `UnauthorizedException` (401), `GlobalExceptionHandler` | Maps domain, validation, optimistic-locking (`STALE_VERSION`) and unexpected errors into RFC 9457 `ProblemDetail` JSON |
| `common/paging/` | `PageResponse<T>` | Stable DTO wrapper converted via `PageResponse.from(Page<T>)` so Spring's internal `PageImpl` JSON structure is never exposed |
| `common/audit/` | `AuditService`, `LoggingAuditService` (replaced in W4 by `DatabaseAuditService`) | Contract for recording sensitive admin and system actions |
| `storage/` | `StorageService`, `LocalStorageService` (`@Profile("!prod")`) | W3 non-prod stub logging generated `<uuid>.<ext>` keys (real disk persistence + validation planned for W5, #71) |
| `notification/` | `NotificationService`, `LoggingNotificationService` (`@Profile("!prod")`) | Logs outbound user notifications in dev/test; paired with SES in `prod` |

### Key design choices

| Choice | Why |
|---|---|
| RFC 9457 `ProblemDetail` + `code` property | Built into Spring Framework 6/7; gives the React client a stable `code` key (`SERIAL_NUMBER_TAKEN`, `RESERVATION_CONFLICT`) independent of human text |
| `@Profile("!prod")` instead of `@Profile("dev")` on local adapters | Ensures `@SpringBootTest` (which runs under the `test` profile) also gets a working `StorageService` and `NotificationService` bean without extra mocks |
| Catch-all `Exception` handler logs server-side only | Returns generic `500 INTERNAL_ERROR` to the client while delegating Spring's own `ErrorResponse` exceptions (`404`/`405`) cleanly |

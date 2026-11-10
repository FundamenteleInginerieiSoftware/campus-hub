# 16 — Rejecting Timezone Offsets in Audit Log Date Filters

**Issue:** [#241](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/241)  
**Date:** 9 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

In the Campus Equipment & Incident Hub, all dates and times across the system represent local campus time (Bucharest, Europe/Bucharest). The system API contract strictly expects local timestamps without timezone offsets or UTC designators (like `Z` or `+05:00`).

Previously, the audit log search endpoint accepted filter dates with timezone markers (e.g. `2026-11-09T20:09:00Z` or `+05:00`), but silently stripped the timezone information and treated the timestamp as campus local time. This could cause confusion or incorrect log filtering when clients send timestamps with offsets.

With this fix, any filter timestamp containing an explicit timezone offset (`Z` or `+HH:MM`) is rejected immediately with a `400 Bad Request` and error code `INVALID_PARAMETER`, providing clear feedback and matching the behavior established across the rest of the application.

---

## Part 2: Technical Details

### Problem Analysis
In `AdminAuditLogController.java`, the query parameters `from` and `to` were annotated with:
```java
@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
LocalDateTime from
```
Spring's default `ISO.DATE_TIME` parser parses standard ISO-8601 strings, including those with timezone offsets or `Z`. When binding to a `LocalDateTime`, the offset was silently ignored and discarded without error.

### Solution
1. Changed the `@DateTimeFormat` pattern on both `from` and `to` in `AdminAuditLogController.java` to:
   ```java
   @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
   LocalDateTime from,
   @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
   LocalDateTime to
   ```
   This pattern strictly matches local date-time strings without offsets (with optional seconds) and causes Spring Web's conversion service to fail with `MethodArgumentTypeMismatchException` whenever an offset (`Z` or numeric `+HH:MM`) is supplied.
2. `GlobalExceptionHandler` converts this conversion error into RFC 9457 `ProblemDetail` with status `400 Bad Request` and `code: "INVALID_PARAMETER"`.
3. Updated OpenAPI parameter documentation in `AdminAuditLogController.java` and `docs/api-contract.md`.
4. Added unit/integration tests in `AdminAuditLogControllerTest.java` verifying that both `from` with `Z` and `to` with `+05:00` return 400 `INVALID_PARAMETER`.

### Verification Matrix

| Endpoint | Parameter | Value | HTTP Status | Error Code | Detail |
|---|---|---|---|---|---|
| `GET /api/v1/admin/audit-logs` | `from` | `2026-11-01T00:00:00` | `200 OK` | — | Valid local timestamp |
| `GET /api/v1/admin/audit-logs` | `from` | `2026-11-09T20:09:00Z` | `400 Bad Request` | `INVALID_PARAMETER` | `Invalid value for parameter 'from'` |
| `GET /api/v1/admin/audit-logs` | `to` | `2026-11-09T20:09:00+05:00` | `400 Bad Request` | `INVALID_PARAMETER` | `Invalid value for parameter 'to'` |
| `GET /api/v1/admin/audit-logs` | `from` | `not-a-timestamp` | `400 Bad Request` | `INVALID_PARAMETER` | `Invalid value for parameter 'from'` |

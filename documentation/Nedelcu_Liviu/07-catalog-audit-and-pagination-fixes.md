# Fixes: Page Offset Bounds, Audit Error Isolation, and Error Contract (Nedelcu Liviu)

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)  
**Date:** 2026-11-05  
**Branch / PR:** `fix/catalog-audit-and-pagination-bugs` (Issues #119, #120, #121)  
**See also:** `01-shared-foundations.md`, `04-catalog-admin-crud.md`, `06-catalog-asset-search.md`

---

## Part 1: In simple terms

1. **Large page numbers (#119):** If a user or script asked for a gigantic page number like 999,999,999, the server multiplied `page * size` and overflowed the 32-bit integer limit, resulting in a 500 server crash. We now check this upfront and reply with a clean 400 Bad Request (`INVALID_PARAMETER`).
2. **Audit log errors (#120):** When creating or updating a category, room, or asset, we write an audit record. Previously, the code wrapped both the database save and the audit call in one try-catch block for duplicate names. If the audit log failed (e.g. invalid actor ID), the system incorrectly blamed the category name and told the user "Category already exists" (`CATEGORY_NAME_TAKEN`). We moved the audit call outside that block so duplicate checks only catch real duplicate items.
3. **Error contract alignment (#121):** RFC 9457 requires error objects to include a standard type URI (`"type": "about:blank"`). We set this explicitly across all error responses. We also mapped missing URL query parameters to `INVALID_PARAMETER` and documented default Spring MVC errors (`BAD_REQUEST`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`) in the team API contract.

---

## Part 2: Technical details

### Changes by Component

| Component | Files | Change |
|---|---|---|
| Large offset guard (#119) | `catalog/service/AssetService.java` | Guard `(long) page * size > Integer.MAX_VALUE` throwing `InvalidParameterException` |
| Audit error isolation (#120) | `CategoryService.java`, `LabRoomService.java`, `AssetService.java` | Move `auditService.record(...)` after the `try/catch (DataIntegrityViolationException)` block |
| Error format & mapping (#121) | `GlobalExceptionHandler.java` | Set `problem.setType(URI.create("about:blank"))`, add handler for `MissingServletRequestParameterException` -> `INVALID_PARAMETER` |
| API Contract update (#121) | `docs/api-contract.md` | Document `BAD_REQUEST`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE` in §5 Generic table |

### Verification

| Test Class | Focus |
|---|---|
| `AssetServiceTest` | Verifies rejection of huge offset (`page * size > Integer.MAX_VALUE`), verifies concurrent duplicate serial 409, verifies audit failure is not caught as duplicate |
| `CategoryServiceTest` | Verifies concurrent duplicate name 409, verifies audit failure is not caught as duplicate name, verifies audit failure on delete is not caught as category in use |
| `LabRoomServiceTest` | Verifies audit failure on room create is not caught as duplicate code |
| `GlobalExceptionHandlerTest` | Verifies `type == URI.create("about:blank")` and verifies `MissingServletRequestParameterException` maps to 400 `INVALID_PARAMETER` |

`./gradlew spotlessApply build` completed with 0 failures across 365+ tests.

# Catalog Query Optimizations, Timestamp Seconds, and Constraint Verification (Nedelcu Liviu)

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)  
**Date:** 2026-11-06  
**Branch / PR:** `fix/catalog-audit-and-optimizations` (Issues #147, #160, #167)  
**See also:** `03-database-audit-service.md`, `04-catalog-admin-crud.md`, `07-catalog-audit-and-pagination-fixes.md`

---

## Part 1: In simple terms

1. **Second truncation for audit logs (#147):** Modern Java records time down to nanoseconds, but the PostgreSQL database only stores microseconds (and our API contract uses whole seconds). By truncating to seconds in `DatabaseAuditService`, dates in memory match what was saved in the database, avoiding subtle equality check bugs.
2. **Preventing useless queries (#160):**
   - When an asset is already retired, an update request cannot succeed. We now check this immediately, avoiding 2 unnecessary database queries looking up categories and rooms that would just be discarded.
   - When retiring an asset, we now fetch the category and room together in one query using an entity graph, instead of doing 2 extra lazy queries right after.
   - For category lookups, we filter directly on the foreign key column rather than doing an unnecessary SQL table join.
3. **Accurate error reporting on edge cases (#167):**
   - Certain special characters expand into multiple letters when converted to uppercase (e.g. German `ß` becomes `SS`). We ensure the uppercased text does not exceed the column length, returning a clean 400 Bad Request if it does.
   - If saving an entity causes a database error, we verify that the code/serial/name is actually a duplicate before claiming it already exists. If it was another error (like a deleted category), we no longer lie to the user.

---

## Part 2: Technical details

### Changes by File

| File | Change | Issue |
|---|---|---|
| `common/audit/DatabaseAuditService.java` | Truncate `LocalDateTime.now(clock)` to `ChronoUnit.SECONDS` | #147 |
| `catalog/repository/AssetRepository.java` | Add `@EntityGraph` to `findByIdForUpdate`; add explicit JPQL `@Query` on `a.category.id` to avoid join | #160 |
| `catalog/service/AssetService.java` | Guard `normalizedSerial.length() > 100`; verify duplicate in catch; check `RETIRED` early in `update` | #160, #167 |
| `catalog/service/LabRoomService.java` | Guard `normalizedCode.length() > 20`; verify duplicate in catch before throwing 409 | #167 |
| `catalog/service/CategoryService.java` | Verify duplicate in catch before throwing 409 | #167 |

### Tests Added / Updated

- `DatabaseAuditServiceTest`: verifies clock with sub-second nanos is truncated to seconds.
- `AssetServiceTest`: verifies character expansion rejection, verifies 0 FK queries on retired asset update, verifies true race vs non-duplicate rethrow.
- `LabRoomServiceTest`: verifies code length expansion rejection, verifies race vs non-duplicate rethrow.
- `CategoryServiceTest`: verifies race vs non-duplicate rethrow.

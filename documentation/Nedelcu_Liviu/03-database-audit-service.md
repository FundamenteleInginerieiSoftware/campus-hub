# Database Audit Service (Nedelcu Liviu)

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)
**Date:** 2026-10-29
**Branch / PR:** `feat/common-audit-service` (#64)
**See also:** `01-shared-foundations.md` (the `AuditService` contract)

---

## Part 1: In simple terms

In Week 2, `LoggingAuditService` only printed audit entries to the console, so they vanished whenever the server restarted. This PR replaces it with a real database table (`audit_logs`) and `DatabaseAuditService`:

1. **Permanent audit trail in PostgreSQL:** Every recorded action stores who did it (`actor_id`), what happened (`action`), which item was affected (`entity_type`, `entity_id`), human-readable `details`, and the exact campus timestamp (`created_at`).
2. **Ready for both admins and background jobs:** Human actions store the user's ID, and the nullable `actor_id` column can also record system jobs (`actor_id` `null`) without breaking foreign-key integrity.
3. **Rolls back if the action fails:** Because the audit write runs inside the caller's transaction, a failed operation never leaves a misleading audit entry behind.

---

## Part 2: Technical details

### Files

| File | Purpose |
|---|---|
| `db/migration/V2026_10_29_1140__audit_create_audit_logs.sql` | Creates `audit_logs` with `actor_id BIGINT REFERENCES users(id)` and indexes on `created_at DESC` and `(entity_type, entity_id)` |
| `common/audit/AuditLog.java` | Immutable JPA entity (`@Getter`, `protected` no-arg constructor, `updatable = false` on `created_at`) |
| `common/audit/AuditLogRepository.java` | Spring Data `JpaRepository<AuditLog, Long>` |
| `common/audit/DatabaseAuditService.java` | `@Service` implementing `AuditService` with the shared `Clock`; replaces the deleted `LoggingAuditService` |
| `test/.../common/audit/DatabaseAuditServiceTest.java`, `AuditLogRepositoryTest.java` | Mockito unit tests (admin actor + `null` system actor) and `@DataJpaTest` against `campus_test` (null actor + FK violation on unknown `actorId`) |

### Key design choices

| Choice | Why |
|---|---|
| Plain `Long actorId` in Java + SQL FK `REFERENCES users(id)` | `common` must not import the `user` module, while PostgreSQL still rejects nonexistent user IDs |
| Default `@Transactional` (`Propagation.REQUIRED`) on `record(...)` | Joins the caller's transaction so an exception after `record(...)` rolls back both the domain change and its audit entry |
| `LocalDateTime.now(clock)` + SQL `TIMESTAMP` | Matches team decision (`decisions.md` row 51): timestamps are stored consistently in `Europe/Bucharest` even when AWS runs in UTC |
| Deleted `LoggingAuditService` instead of `@Profile` split | PostgreSQL runs in every environment (local dev, CI `campus_test`, AWS RDS), so one implementation serves all profiles without `NoUniqueBeanDefinitionException` |

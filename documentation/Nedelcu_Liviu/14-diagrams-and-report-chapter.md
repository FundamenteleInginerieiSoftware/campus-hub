# 14 — Final UML/ER Diagrams & FIS Report Chapter (Catalog & Shared Foundations)

**Author:** Liviu Nedelcu (`@NliviuN`)  
**Date:** 2026-11-08 (W6)

---

## Part 1: In simple terms (for non-programmers)

For the final FIS defense and project report, each team member documents the architecture, business rules, and design decisions of their owned modules, accompanied by visual diagrams that match the production codebase and database migrations.

This entry adds:
1. **Asset Lifecycle State Diagram (`docs/diagrams/asset-lifecycle.mmd`):** Shows how a piece of lab equipment moves between `AVAILABLE`, `UNDER_MAINTENANCE` (when a student reports an incident and when an admin resolves/rejects it), and `RETIRED` (the permanent terminal state when an admin retires it).
2. **Catalog & Shared Services Class Diagram (`docs/diagrams/catalog-and-common-class.mmd`):** Shows the public `CatalogApi` facade, catalog domain entities (`Category`, `LabRoom`, `Asset`, `AssetStatus`), services, and the shared packages (`AppException`, `GlobalExceptionHandler`, `PageResponse<T>`, `AuditService`, `StorageService`, `NotificationService`).
3. **Database Entity-Relationship (ER) Diagram (`docs/diagrams/database-er.mmd`):** Shows all 8 PostgreSQL 18 tables (`users`, `categories`, `lab_rooms`, `assets`, `reservations`, `incident_reports`, `reassignment_log`, `audit_logs`) with their columns, primary keys, unique constraints, and foreign keys as defined in Flyway migrations.
4. **Final Report Chapter (`docs/report/catalog-and-shared-services.md`):** Comprehensive chapter covering catalog domain rules, pessimistic vs optimistic locking, N+1 query prevention (`@EntityGraph` + `Specification`), RFC 9457 error handling, transactional audit logging, storage magic-byte validation, frontend pages, and PIT mutation testing results.

---

## Part 2: Technical details

### Files added & modified

| File | Purpose |
|---|---|
| `docs/diagrams/asset-lifecycle.mmd` | Mermaid `stateDiagram-v2` for `AssetStatus` (`AVAILABLE`, `UNDER_MAINTENANCE`, `RETIRED`). |
| `docs/diagrams/catalog-and-common-class.mmd` | Mermaid `classDiagram` for `com.campus.catalog`, `com.campus.common`, `com.campus.storage`, and `com.campus.notification`. |
| `docs/diagrams/database-er.mmd` | Mermaid `erDiagram` verified column-for-column against `backend/src/main/resources/db/migration/*.sql`. |
| `docs/diagrams/README.md` | Registers the 3 new diagrams under Owner: `Liviu`. |
| `docs/report/catalog-and-shared-services.md` | Member 2 chapter of the final FIS report. |

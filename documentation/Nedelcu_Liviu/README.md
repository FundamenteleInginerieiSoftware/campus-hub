# Nedelcu Liviu — Personal Documentation

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)

## Owned areas
- **Shared services:** `common/error` (shared exceptions + `GlobalExceptionHandler` with RFC 9457 `ProblemDetail`), `common/paging` (`PageResponse`), `common/audit` (`AuditService` + `DatabaseAuditService`), `storage/` (`StorageService` + `LocalStorageService`), `notification/` (`NotificationService` + `LoggingNotificationService`).
- **Catalog module:** `catalog/` (`Category`, `LabRoom`, `Asset`, `CatalogApi`, pessimistic row locking for booking; planned W4–W7: admin CRUD & retirement, asset search/filter/pagination, dev seeder, audit log endpoint).
- **Frontend pages (planned W6–W7):** equipment catalog, asset details, admin inventory management, and admin audit log view.

## Log entries (merged on `main`)

| # | File | Milestone / PR |
|---|---|---|
| 01 | [01-shared-foundations.md](01-shared-foundations.md) | Shared error handling (`ProblemDetail`), `PageResponse`, and Audit/Storage/Notification interfaces & dev adapters (#22) |
| 02 | [02-catalog-domain-and-api.md](02-catalog-domain-and-api.md) | Catalog tables, JPA entities, pessimistic booking lock, and `CatalogApi` facade (#24) |
| 03 | [03-database-audit-service.md](03-database-audit-service.md) | PostgreSQL `audit_logs` migration and `DatabaseAuditService` (#64) |
| 04 | [04-catalog-admin-crud.md](04-catalog-admin-crud.md) | Category, lab room, and asset admin CRUD, asset details, and retire flow (#66, #73, #89) |
| 05 | [05-local-storage-service.md](05-local-storage-service.md) | Local disk storage with magic-byte, size, and path-traversal security checks (#90) |
| 06 | [06-catalog-asset-search.md](06-catalog-asset-search.md) | Paginated asset search and filtering with `Specification` and `@EntityGraph` (#92) |
| 07 | [07-catalog-audit-and-pagination-fixes.md](07-catalog-audit-and-pagination-fixes.md) | Fixes: Page offset bounds, audit error isolation, and error contract (#119, #120, #121) |
| 08 | [08-catalog-optimizations-and-edge-cases.md](08-catalog-optimizations-and-edge-cases.md) | Catalog query optimizations, timestamp seconds, and constraint verification (#147, #160, #167, PR #192) |
| 09 | [09-dev-catalog-seeder.md](09-dev-catalog-seeder.md) | Development catalog seeder with multi-unit models for automated reassignment demos (#193) |
| 10 | [10-catalog-frontend-pages.md](10-catalog-frontend-pages.md) | Equipment catalog, asset details, and admin inventory frontend pages (#196) |
| 11 | [11-admin-audit-log-endpoint.md](11-admin-audit-log-endpoint.md) | Administrative audit log search and filtering endpoint (`GET /api/v1/admin/audit-logs`, #217) |
| 12 | [12-catalog-swagger-and-pit-mutation-tests.md](12-catalog-swagger-and-pit-mutation-tests.md) | Catalog Swagger error codes, `STALE_VERSION` optimistic lock test, and PIT mutation tests (#187, #220) |
| 13 | [13-admin-audit-log-frontend.md](13-admin-audit-log-frontend.md) | Administrator audit log frontend page (`/admin/audit-logs`, #221) |
| 14 | [14-diagrams-and-report-chapter.md](14-diagrams-and-report-chapter.md) | Final UML/ER diagrams (`asset-lifecycle`, `catalog-and-common-class`, `database-er`) and FIS report chapter (`catalog-and-shared-services.md`, #222) |
| 15 | [15-catalog-spinner-audit-filters-and-seeder-fixes.md](15-catalog-spinner-audit-filters-and-seeder-fixes.md) | Bug fixes: Catalog debounce spinner, audit filter actions/entities, and seeder asset 14 status (#230) |
| 16 | [16-audit-log-timezone-offset-rejection.md](16-audit-log-timezone-offset-rejection.md) | Reject timezone offsets in audit log date filters with 400 `INVALID_PARAMETER` (#241) |
| 17 | [17-project-kit-models-seeder.md](17-project-kit-models-seeder.md) | Seed student project hardware kit models for the Projects Assistant (#140) |

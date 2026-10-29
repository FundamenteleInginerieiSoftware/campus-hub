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

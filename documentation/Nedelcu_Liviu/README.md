# Nedelcu Liviu — Personal Documentation

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)

## Owned areas
- **Shared services:** `common/error` (shared exceptions + `GlobalExceptionHandler` with RFC 9457 `ProblemDetail`), `common/paging` (`PageResponse`), `common/audit` (`AuditService` + `DatabaseAuditService`), `storage/` (`StorageService` + `LocalStorageService`), `notification/` (`NotificationService` + `LoggingNotificationService`).
- **Catalog module:** `catalog/` (`Category`, `LabRoom`, `Asset`, `CatalogApi`, search/filter/pagination, row locking for booking, admin CRUD & retirement).
- **Frontend pages:** equipment catalog, asset details, admin inventory management, and admin audit log view.

## Log entries

| # | File | Milestone / PR |
|---|---|---|
| 01 | [01-shared-foundations.md](01-shared-foundations.md) | Shared error handling (`ProblemDetail`), `PageResponse`, and Audit/Storage/Notification interfaces & dev adapters (#22) |
| 02 | [02-catalog-domain-and-api.md](02-catalog-domain-and-api.md) | Catalog tables, JPA entities, pessimistic booking lock, and `CatalogApi` facade (#24) |
| 03 | [03-audit-log-and-admin-crud.md](03-audit-log-and-admin-crud.md) | PostgreSQL `DatabaseAuditService` (#64) and Catalog Admin CRUD + Retire (#66, `feat/catalog-admin-assets`) |

# Nedelcu Liviu — Personal Documentation

**Role:** Member 2 — Catalog, Inventory & Shared Services (`@NliviuN`)

## Owned areas
- **Shared services:** `common/error` (shared exceptions + `GlobalExceptionHandler` with RFC 9457 `ProblemDetail`), `common/paging` (`PageResponse`), `common/audit` (`AuditService`), `storage/` (`StorageService` + `LocalStorageService`), `notification/` (`NotificationService` + `LoggingNotificationService`).
- **Catalog module:** `catalog/` (`Category`, `LabRoom`, `Asset`, `CatalogApi`, search/filter/pagination, row locking for booking, admin CRUD).
- **Frontend pages:** equipment catalog, asset details, admin inventory management, and admin audit log view.

## Next two weeks (W3–W4)
1. Deliver the shared foundations (`common/error`, `PageResponse`, and service interfaces/stubs for audit, storage, and notifications) to unblock the team.
2. Migrate the catalog domain entities (`Category`, `LabRoom`, `Asset`) and Flyway migrations into `backend/` for the entity freeze, along with the `CatalogApi` stub.
3. Implement admin CRUD for catalog entities and the real `AuditService` backed by PostgreSQL.

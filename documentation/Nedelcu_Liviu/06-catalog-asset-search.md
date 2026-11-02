# 06 — Paginated Asset Search & Filtering (`GET /api/v1/assets`)

## Part 1: In simple terms

Students browsing the equipment catalog need to search by name, model, or serial number, narrow down by category, lab room, or operational status, and browse pages cleanly without seeing retired items. Lab admins opening the inventory screen need the same search and filters, including retired equipment.

What was added:
- A single search endpoint (`GET /api/v1/assets`) that accepts optional filters (`search`, `categoryId`, `labRoomId`, `status`) together with `page`, `size`, and `sort`.
- Automatic visibility filtering: students never see `RETIRED` items in search results, while admins see the entire inventory.
- Fast database loading: each page fetches the equipment together with its category and lab room in **one** SQL query instead of running dozens of extra queries per page.
- Safe input validation: invalid page numbers, oversized page requests (`> 100`), or unknown sort fields return a clean `400 Bad Request` (`INVALID_PARAMETER`).

---

## Part 2: Technical details

| Component | Responsibility |
|---|---|
| `InvalidParameterException` (`common.error`) | Extends `AppException` (`400 Bad Request`, `code = "INVALID_PARAMETER"`) for invalid pagination or sort parameters |
| `AssetSpecifications` (`catalog.repository`) | Builds dynamic JPA Criteria `Specification<Asset>` predicates without `(:param is null)` JPQL tricks (which fail type inference on PostgreSQL); escapes `%`, `_`, and `\` wildcards in `LIKE` patterns |
| `AssetRepository.findAll(Specification, Pageable)` | Annotated with `@EntityGraph(attributePaths = {"category", "labRoom"})` so mapping `Page<Asset>` to `PageResponse<AssetResponse>` triggers zero N+1 queries with `spring.jpa.open-in-view=false` |
| `AssetService.search(...)` | Enforces `page >= 0`, `1 <= size <= 100`, and a sort whitelist (`name`, `model`, `serialNumber`, `status`, `createdAt`, `updatedAt`) with a secondary `id ASC` tie-breaker; sets `excludeRetired = !currentUser.isAdmin()` |
| `AssetController.search(...)` | Exposes `GET /api/v1/assets` returning `PageResponse<AssetResponse>` |
| `AssetRepositoryTest` / `AssetServiceTest` / `AssetControllerTest` | Verifies filter combinations + detached `@EntityGraph` mapping on PostgreSQL 18 (`campus_test`), sort whitelist & pagination bounds in Mockito, and HTTP `200`/`400`/`401` contracts in `@WebMvcTest` |

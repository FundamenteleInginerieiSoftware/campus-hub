# Member 2: Asset Catalog & Inventory Lead

> Read `00-TEAM-HANDBOOK.md` first. This file only covers what is specific to you.
> Your module is the **foundation of the domain**. Reservations (Member 3), Incidents and Reassignment (Member 4) all build on your `Asset`. Your most important output is not the CRUD itself but a **stable, well-designed `CatalogApi` delivered early**.

---

## 1. Project in one paragraph

Campus Equipment & Incident Hub: students book lab equipment by time slot (overlaps rejected) and report broken equipment with photos. Damaged assets are locked automatically. An auto-reassignment engine moves future bookings of a broken asset to a substitute, or force-cancels them, and notifies the students. Spring Boot 4.1 / Java 21 backend (modular monolith), React frontend, H2 locally and PostgreSQL on AWS (deployed by Matei).

## 2. What you own

**Package:** `com.campus.catalog` (`api/`, `domain/`, `repository/`, `service/`, `web/`)

| Item | Details |
|---|---|
| Entities | `Category` (name, description), `LabRoom` (code, name, building), `Asset` (name, **model**, serialNumber, description, status, category, labRoom, timestamps, `@Version`) |
| Enum | `AssetStatus { AVAILABLE, UNDER_MAINTENANCE, RETIRED }`. **Operational status only** (see handbook §10: no `RESERVED`/`IN_USE`) |
| Student endpoints | Browse/search/filter assets (paginated), asset details, list categories and lab rooms |
| Admin endpoints | CRUD categories, lab rooms, assets; retire an asset |
| Public facade | `CatalogApi` + `AssetSummary` (used by Members 3 and 4) |
| Concurrency helper | `lockForBooking(assetId)`: pessimistic row lock used by the reservation module |
| Seed data | `@Profile("dev") @Order(2) CatalogSeeder` |
| Migrations | `categories`, `lab_rooms`, `assets` tables + indexes |
| Docs (lead) | **Domain class diagram**, **ER diagram**, Asset state diagram, `docs/modules/catalog.md` |

Your module has the simplest business logic, so you also **lead the class/ER diagrams for the whole system** and are the natural helper for Member 4 (frontend catalog/inventory pages, substitute queries). Agree with Member 4 after M1.

---

## 3. How your work connects to others

| Who | Uses from you | Notes |
|---|---|---|
| **Member 3 (Reservations)** | `getAsset`, `isBookable`, `lockForBooking` | Called on **every** booking. Must be fast and correct. `isBookable` = exists && `AVAILABLE`. |
| **Member 4 (Incidents)** | `getAsset`, `markUnderMaintenance`, `markAvailable` | Called when an incident is reported/resolved. Must be **idempotent** (marking an asset already in maintenance is not an error). |
| **Member 4 (Reassignment)** | `findAvailableByModel(model, excludeId)`, `findAvailableByCategory(categoryId, excludeId)` | Return only `AVAILABLE` assets, excluding the damaged one. Order them so results are deterministic (e.g. same lab room first, then by id). |
| **Member 4 (Frontend)** | REST endpoints + JSON examples in `docs/api-contract.md` | Write the TS types/API functions for your endpoints in `frontend/src/api/catalog.ts` + `types/` if Member 4 agrees. It helps them a lot. |
| **Matei** | Gives you: `common.error` exceptions, `PageResponse`, `AuditService`, `CurrentUser`, security (any `/api/v1/admin/**` path is admin-only automatically) | Ask Matei for dependencies (don't edit `pom.xml`). |

**Rule:** others never import your `domain/`, `repository/` or `service/` packages, only `catalog.api`. In turn, **your module must never import reservation/incident/reassignment**. Example: "which assets are free on Tuesday 14–16?" needs reservation data, so that endpoint belongs to **Member 3** (`/api/v1/reservations/availability`), not to you.

---

## 4. API (draft: confirm in `docs/api-contract.md` in week 2)

| Method & path | Who | Description |
|---|---|---|
| `GET /api/v1/assets?q=&categoryId=&labRoomId=&status=&page=0&size=20&sort=name,asc` | logged in | Paginated search. Students see `AVAILABLE` + `UNDER_MAINTENANCE` (greyed out), not `RETIRED` |
| `GET /api/v1/assets/{id}` | logged in | Details (category name, room, status) |
| `GET /api/v1/categories` | logged in | All categories (small list, no paging) |
| `GET /api/v1/lab-rooms` | logged in | All rooms |
| `POST /api/v1/admin/assets` | admin | Create (201 + body) |
| `PUT /api/v1/admin/assets/{id}` | admin | Update name/model/description/category/room (+ `version` for optimistic locking) |
| `POST /api/v1/admin/assets/{id}/retire` | admin | Status → `RETIRED` (soft delete) |
| `GET /api/v1/admin/assets?…` | admin | Like search, but includes `RETIRED` |
| `POST/PUT/DELETE /api/v1/admin/categories[/{id}]` | admin | DELETE → 409 `CATEGORY_IN_USE` if it still has assets |
| `POST/PUT/DELETE /api/v1/admin/lab-rooms[/{id}]` | admin | DELETE → 409 `LAB_ROOM_IN_USE` if it still has assets |

Error codes you own: `ASSET_NOT_FOUND`, `CATEGORY_NOT_FOUND`, `LAB_ROOM_NOT_FOUND`, `SERIAL_NUMBER_TAKEN`, `CATEGORY_NAME_TAKEN`, `CATEGORY_IN_USE`, `LAB_ROOM_IN_USE`, `ASSET_RETIRED`, `INVALID_ASSET_STATUS_TRANSITION`.

**Design decision:** admins **cannot** manually set `UNDER_MAINTENANCE` through your API. Maintenance always goes through an **incident** (an admin can file one too). Otherwise an asset could enter maintenance without triggering the reassignment engine, and its future bookings would stay on a broken asset.

---

## 5. Step-by-step guide

### Week 1: Learn and set up
1. Install JDK 21 (Temurin), IntelliJ IDEA, Git. Set `git config --global user.name/user.email` (use your GitHub email!).
2. Clone the repo, run `./mvnw spring-boot:run`, open Swagger (`/swagger-ui.html`) and the H2 console (`/h2-console`, JDBC URL from `application-dev.yml`).
3. Do the spring.io guides "Building a RESTful Web Service" and "Accessing Data with JPA" (handbook §14).
4. Practice PR: add your name to the README.

### Week 2: Requirements & design
1. Write user stories for your module in `docs/requirements/catalog.md`, each with acceptance criteria. Example:
   > *As a student I want to filter equipment by category and lab room so that I quickly find a kit for my assignment.*
   > Given 3 oscilloscopes in room B204 and 2 in C101, when I filter by category "Oscilloscopes" and room "B204", then I see exactly the 3 from B204, sorted by name.
2. Draft the **class diagram** and **ER diagram** for the whole system (PlantUML/Mermaid in `docs/diagrams/`). Use the entity list from handbook §10. Ask each owner to check their part.
3. Write the `CatalogApi` interface + `AssetSummary` record (signatures from handbook §11) and agree on them with Members 3 and 4.
4. Prepare the catalog part of the **entity freeze**.

### Week 3: Entity freeze + stub API
1. Migration (timestamp-named, see handbook §8.2), e.g. `V2026_10_20_1000__catalog_create_tables.sql`:
   ```sql
   CREATE TABLE categories (
     id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
     name VARCHAR(100) NOT NULL UNIQUE,
     description VARCHAR(500)
   );
   CREATE TABLE lab_rooms (
     id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
     code VARCHAR(20) NOT NULL UNIQUE,
     name VARCHAR(100) NOT NULL,
     building VARCHAR(100)
   );
   CREATE TABLE assets (
     id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
     name VARCHAR(150) NOT NULL,
     model VARCHAR(150) NOT NULL,
     serial_number VARCHAR(100) NOT NULL UNIQUE,
     description TEXT,
     status VARCHAR(32) NOT NULL,
     category_id BIGINT NOT NULL REFERENCES categories(id),
     lab_room_id BIGINT NOT NULL REFERENCES lab_rooms(id),
     created_at TIMESTAMP NOT NULL,
     updated_at TIMESTAMP NOT NULL,
     version BIGINT NOT NULL DEFAULT 0
   );
   CREATE INDEX idx_assets_category ON assets(category_id);
   CREATE INDEX idx_assets_lab_room ON assets(lab_room_id);
   CREATE INDEX idx_assets_model ON assets(model);
   CREATE INDEX idx_assets_status ON assets(status);
   ```
2. Entities: `@Enumerated(EnumType.STRING)` for status; `@ManyToOne(fetch = LAZY)` for category/labRoom; `@Version Long version`; Lombok `@Getter` + protected no-args constructor (no `@Data`!). Set `createdAt/updatedAt` from the shared `Clock` in the service (or with `@PrePersist`/`@PreUpdate`).
3. **Put the state transitions inside `Asset`** (rich domain model, good for the report):
   ```java
   public void markUnderMaintenance() {
       if (status == AssetStatus.RETIRED) throw new BusinessRuleException("ASSET_RETIRED", "Asset is retired");
       status = AssetStatus.UNDER_MAINTENANCE;          // idempotent: already in maintenance → stays
   }
   public void markAvailable() {
       if (status == AssetStatus.RETIRED) throw new BusinessRuleException("ASSET_RETIRED", "Asset is retired");
       status = AssetStatus.AVAILABLE;
   }
   public void retire() { status = AssetStatus.RETIRED; }
   ```
4. Merge a **stub** `CatalogService implements CatalogApi` early. Even if some methods are simple, Members 3 and 4 can then code against it.

### Week 4: Admin CRUD
1. Request/response records in `web/`: `CreateAssetRequest(@NotBlank name, @NotBlank model, @NotBlank serialNumber, description, @NotNull categoryId, @NotNull labRoomId)`, `UpdateAssetRequest(…, @NotNull Long version)`, `AssetResponse(id, name, model, serialNumber, description, status, categoryId, categoryName, labRoomId, labRoomCode, version)`.
2. Services with `@Transactional`. Check uniqueness (`existsBySerialNumberIgnoreCase`) → 409 `SERIAL_NUMBER_TAKEN`. Also catch `DataIntegrityViolationException` as a backstop (race between two admins).
3. Normalize input: trim strings; serial numbers uppercase.
4. Record admin actions with `AuditService.record(CurrentUser.id(), "ASSET_CREATED", "Asset", id, "...")`.
5. Tests: service unit tests (Mockito) + `@WebMvcTest` (201, 400 validation, 403 for a student on `/admin/**`, 404, 409).

### Week 5: Search & filters + real CatalogApi
1. **Search with optional filters.** Use **Spring Data `Specification`** (`AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset>`) and build predicates only for the filters that are present.
   - Avoid JPQL like `where (:q is null or lower(a.name) like lower(concat('%', :q, '%')))`. On **PostgreSQL** it fails with *"could not determine data type of parameter"*, while it works on H2. This is a classic "works locally, breaks on AWS" trap.
   - `q` searches name, model and serial number (case-insensitive `like`). Escape `%` and `_` in user input, or accept the risk and document it.
2. Avoid **N+1 queries** (1 query for 20 assets + 20 for categories + 20 for rooms): use `@EntityGraph(attributePaths = {"category", "labRoom"})` on the search method, or a fetch join. Check with `show-sql` in dev.
3. **Sort whitelist:** sorting by an unknown property (`sort=foo`) throws `PropertyReferenceException` → 500. Allow only `name`, `model`, `createdAt`, `status` → otherwise 400. Limit `size` to ≤ 100.
4. Return `PageResponse<AssetResponse>` (Matei's shared record), never Spring's `Page` directly.
5. Real implementations: `findAvailableByModel`, `findAvailableByCategory` (only `AVAILABLE`, exclude the given id, deterministic order), `markUnderMaintenance`, `markAvailable`.
6. **`lockForBooking`:**
   ```java
   @Lock(LockModeType.PESSIMISTIC_WRITE)
   @Query("select a from Asset a where a.id = :id")
   Optional<Asset> findByIdForUpdate(@Param("id") Long id);
   ```
   This must run **inside the caller's transaction** (Member 3's `@Transactional` booking method). Your service method should be `@Transactional(propagation = MANDATORY)`, so it fails loudly if someone calls it without a transaction (otherwise the lock is released immediately and protects nothing). Explain this to Member 3.
7. Tests: `@DataJpaTest` for the specification combinations (no filters, each filter alone, combined, retired excluded for students) and for `findAvailableBy…`.

### Week 6: M1
1. `CatalogSeeder` (`@Profile("dev") @Order(2)`, only if `count()==0`): about 4 categories (Oscilloscopes, Microcontroller kits, 3D printers, VR headsets), 3 lab rooms, 15–20 assets. **Several units of the same model** (e.g. 4× "Rigol DS1054Z") and other models in the same category, so the reassignment engine has substitutes to find in the demo.
2. Help Member 4 wire the catalog page (list + filters + details).
3. Document your module in `docs/modules/catalog.md` (purpose, endpoints, `CatalogApi` contract, how to test).

### Weeks 7–9: Edge cases, retire flow, support
1. Retiring an asset: decide with Members 3 and 4 what happens to its **future reservations**. Recommended (stretch): publish `AssetRetiredEvent` from `catalog.api` and let the reassignment engine treat it like damage. Minimum: block retiring while active future reservations exist? That would need reservation data, which you are not allowed to import. So use the event approach, or have the frontend warn. **Agree on this in week 7.**
2. Optimistic locking: an `UpdateAssetRequest` with a stale `version` → 409 (Matei's handler maps `ObjectOptimisticLockingFailureException`). Test it.
3. Help Member 4 with substitute-query edge cases (no substitute, several, retired ones excluded).
4. Optional stretch: an asset photo (reuse `StorageService`), or simple statistics (`GET /api/v1/admin/stats/assets`: count by status and category).

### Weeks 10–12: Tests, diagrams, defense
1. Coverage ≥ 70% on `catalog.service`. Add tests for every bug found.
2. Finalize the class diagram and ER diagram (they must match the final code and migrations!) + the Asset state diagram.
3. Write your report section: domain model, Repository/DTO/Facade patterns, the Specification pattern for search, optimistic vs. pessimistic locking.
4. Prepare your demo part: admin adds an asset, student filters the catalog.

---

## 6. Things to keep in mind

- **Your API is a contract.** After week 3, never rename or remove a `CatalogApi` method or an `AssetSummary` field without telling Members 3 and 4. Add new methods instead.
- **Never hard-delete assets.** Reservations and incidents reference them via FKs (the delete would fail anyway), and history must remain. Use `RETIRED`.
- **Status vs. availability:** `AVAILABLE` means "operational", not "free right now". Make this clear in the UI text ("Operational" / "In maintenance" / "Retired") and in the report.
- **Students must never see admin data** (e.g. `RETIRED` assets, audit info). Admins use separate `/admin/**` endpoints.
- **Validate IDs that come from requests:** a `categoryId` that doesn't exist → 404 `CATEGORY_NOT_FOUND` (or 400), not a 500 from an FK violation.
- **Time:** `createdAt/updatedAt` come from the shared `Clock`, never `LocalDateTime.now()`.
- **Keep facade methods side-effect free unless named otherwise.** `getAsset` must not change anything; `mark…` methods must be idempotent.

---

## 7. Foreseeable problems (yours)

| Problem | Symptom | Fix / prevention |
|---|---|---|
| `StackOverflowError` / huge JSON | Returning entities with bidirectional relations | DTOs only; no `@OneToMany` from `Category` to assets (query instead) |
| `LazyInitializationException` | 500 when mapping `asset.getCategory().getName()` | Map inside the `@Transactional(readOnly = true)` service method, or use `@EntityGraph` |
| N+1 queries | Slow list, dozens of SQL lines in the log | `@EntityGraph` / fetch join |
| Search works on H2, fails on Postgres | CI/AWS error "could not determine data type" | Specifications, not `:param is null` JPQL tricks |
| `PropertyReferenceException` | 500 on `?sort=xyz` | Sort whitelist → 400 |
| Duplicate serial number | 500 `DataIntegrityViolationException` | Pre-check + catch → 409 |
| Lost update between two admins | One admin's change silently overwritten | `@Version` + `version` in the update request |
| Lock not working | Double bookings in Member 3's concurrency test | `lockForBooking` with `propagation = MANDATORY` inside their transaction |
| Category deletion breaks assets | FK error → 500 | Check `existsByCategoryId` → 409 `CATEGORY_IN_USE` |
| Reassignment can't find substitutes in the demo | Everything force-cancelled | Seed several units per model/category |
| Asset in maintenance never comes back | Stays `UNDER_MAINTENANCE` | Member 4 calls `markAvailable` on resolve (only when no other open incidents). Test the flow together |
| Others import your entities | Coupling, ArchUnit failure | Point them to `catalog.api`; add facade methods if needed |
| ER diagram doesn't match the DB | Professor notices | Regenerate/check the diagram against the migrations in W11 |
| Feeling "my part is just CRUD" | Weak defense | Highlight: Specification-based search, pagination, optimistic + pessimistic locking, facade design, domain state rules, the diagrams you led |

---

## 8. Personal checklist

- [ ] W1: tools + tutorials + practice PR
- [ ] W2: user stories; class + ER diagram drafts; `CatalogApi` agreed
- [ ] W3: migrations + entities (entity freeze) + stub facade merged
- [ ] W4: admin CRUD + validation + audit + tests
- [ ] W5: search/filter/paging (Specifications) + real facade + `lockForBooking` + tests
- [ ] W6: seed data; catalog page wired with Member 4; module doc
- [ ] W7–9: retire flow decision; optimistic locking; support reassignment queries
- [ ] W10–12: ≥70% coverage; final class/ER/state diagrams; report section; demo part

## 9. What you present at the defense
The domain model (class + ER diagrams), the asset lifecycle, how the facade (`CatalogApi`) keeps modules decoupled, search with Specifications + pagination, locking (optimistic for admin edits, pessimistic for bookings), and your tests.

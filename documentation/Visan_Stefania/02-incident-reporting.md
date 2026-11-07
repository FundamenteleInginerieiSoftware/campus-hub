# Reporting a Broken Item: the Incident Module (Vișan Ștefania)

**Role:** Member 4
**Date:** 2026-10-31
**Package:** `com.campus.incident`
**PR:** #40 (`feat(incident): add reporting flow, domain persistence and AssetDamagedEvent`)

---

## Part 1: In simple terms

A student picks up a camera and the screen is cracked. They open the app and **report an incident**: which item, what is wrong, how bad it is (low, medium, high, critical).

Three things happen at once:

1. The report is saved, with status **OPEN**.
2. The item is taken **out of use** (status `UNDER_MAINTENANCE`), so nobody can book it.
3. The app **announces** "item 12 is broken". Calin's reassignment engine hears this and moves the upcoming bookings of that item to a similar free one.

Later an admin looks at the report and **resolves** it (repaired). When no other open report is left on the item, it becomes **AVAILABLE** again.

The module only talks to the catalog through its public "front door" (`CatalogApi`) and to the reassignment engine through an **event**, so the modules stay independent.

---

## Part 2: Technical details

### Files

| Layer | File | What it does |
|---|---|---|
| Event (public) | `incident/api/AssetDamagedEvent.java` | `record(incidentId, assetId, reportedById, occurredAt)` |
| Domain | `incident/domain/IncidentReport.java` | JPA entity; owns the status rules (`resolve`) |
| Repository | `incident/repository/IncidentReportRepository.java` | Spring Data queries |
| Service | `incident/service/IncidentService.java` | the use cases, one transaction each |
| Web | `incident/web/IncidentController.java` | `POST /api/v1/incidents` |
| Web | `incident/web/AdminIncidentController.java` | admin list + resolve |
| DTOs | `CreateIncidentRequest`, `ResolveIncidentRequest`, `IncidentResponse` | JSON in/out; the entity never leaves the module |
| Migration | `db/migration/V2026_10_30_2045__incident_create_tables.sql` | table `incident_reports` |
| Tests | `incident/service/IncidentServiceTest.java` | unit tests with Mockito |

### Table `incident_reports`

| Column | Type | Notes |
|---|---|---|
| `id` | `BIGINT` identity | primary key |
| `asset_id` | `BIGINT NOT NULL` → `assets(id)` | the broken item |
| `reservation_id` | `BIGINT` → `reservations(id)` | optional: found during a booking |
| `reported_by_id` | `BIGINT NOT NULL` → `users(id)` | from the token, never from the body |
| `description` | `TEXT NOT NULL` | max 2000 chars (validated in the DTO) |
| `severity` | `VARCHAR(32)` | `LOW` · `MEDIUM` · `HIGH` · `CRITICAL` (`EnumType.STRING`) |
| `status` | `VARCHAR(32)` | `OPEN` · `IN_REPAIR` · `RESOLVED` · `REJECTED` |
| `photo_key` | `VARCHAR(255)` | storage key of the photo (upload comes in a later PR) |
| `created_at`, `resolved_at` | `TIMESTAMP` | whole seconds, from the shared `Clock` |
| `resolved_by_id`, `resolution_notes` | | who closed it and why |
| `version` | `BIGINT` | optimistic locking (`@Version`) |

Indexes: `asset_id` (the "any open incident on this asset?" check) and `status` (admin filter).

### Endpoints (first version)

| Endpoint | Who | Result |
|---|---|---|
| `POST /api/v1/incidents` | logged in | `201` + `IncidentResponse`; asset → `UNDER_MAINTENANCE` |
| `GET /api/v1/admin/incidents?status=&page=&size=` | admin | page of incidents, newest first |
| `POST /api/v1/admin/incidents/{id}/resolve` | admin | `RESOLVED`; asset back to `AVAILABLE` if no other active incident |

### The report use case, step by step

| Step | Call | Fails with |
|---|---|---|
| 0 | `@Valid CreateIncidentRequest` (asset id, description 1–2000 chars, severity) | 400 `VALIDATION_FAILED` |
| 1 | `catalogApi.getAsset(assetId)` | 404 `ASSET_NOT_FOUND` |
| 2 | save `IncidentReport` with status `OPEN` and `createdAt = now(clock)` cut to seconds | – |
| 3 | `catalogApi.markUnderMaintenance(assetId)` | 409 `ASSET_RETIRED` |
| 4 | `eventPublisher.publishEvent(new AssetDamagedEvent(...))` | – |

All in **one transaction** (`@Transactional`): if step 3 fails, the saved report is rolled back too.

(In #108 the order changed: `markUnderMaintenance` runs first, so the asset row is locked before anything is saved. See doc 05.)

### Observer pattern: `AssetDamagedEvent`

| Side | Code | Runs when |
|---|---|---|
| Publisher (me) | `ApplicationEventPublisher.publishEvent(...)` | inside the report transaction |
| Listener (Calin) | `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` | only **after** the report is committed |

Why after commit: the engine must see the asset already `UNDER_MAINTENANCE`, otherwise it could pick the broken item as its own substitute. And if the report rolls back, no bookings are moved.

The incident module does not import anything from `reassignment`: the event record lives in `incident.api`, the only package other modules may read.

### Design choices

| Choice | Why |
|---|---|
| Status rules in the entity (`resolve`) | One place decides which transitions are allowed; throws `InvalidStateTransitionException` (409 `INVALID_INCIDENT_STATUS_TRANSITION`) |
| Resolving twice returns it unchanged | Idempotent: a double click or a retry is harmless |
| `CatalogApi` instead of the catalog repository | Module boundary, checked by ArchUnit |
| Time from the shared `Clock` | Tests can fix the time; same zone (`Europe/Bucharest`) everywhere |

### Tests (first version)

`IncidentServiceTest` (Mockito, no Spring), 9 tests:

| Test | Checks |
|---|---|
| `reportIncident_shouldSaveMarkAssetUnderMaintenanceAndPublishEvent` | OPEN report saved, asset marked, event has the right ids and time |
| `reportIncident_shouldFailAndSaveNothing_whenAssetDoesNotExist` | 404, nothing saved, no event |
| `resolveIncident_shouldResolveAndMarkAssetAvailable_whenNoOtherActiveIncident` | who/when/notes set, asset AVAILABLE |
| `resolveIncident_shouldKeepAssetUnderMaintenance_whenAnotherIncidentIsStillOpen` | asset stays out of use |
| `resolveIncident_shouldWorkFromInRepair` | IN_REPAIR → RESOLVED allowed |
| `resolveIncident_shouldReturnUnchanged_whenAlreadyResolved` | idempotent, nothing saved |
| `resolveIncident_shouldThrowInvalidStateTransitionException_whenRejected` | 409 from REJECTED |
| `resolveIncident_shouldThrowNotFoundException_whenMissing` | 404 `INCIDENT_NOT_FOUND` |
| `getAllIncidents_shouldFilterByStatusAndReturnPageResponse` | status filter, `PageResponse` |

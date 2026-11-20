# 44 — Asset Retirement Reassignment Flow (#245)

## Part 1: In simple terms

When an administrator retires (decommissions) a piece of lab equipment, students may still have upcoming confirmed bookings on it. Previously, those bookings stayed attached to the retired asset.

Now, when Liviu's catalog service retires an asset and publishes `AssetRetiredEvent`, the reassignment engine automatically reacts after the transaction commits:
- It finds every upcoming confirmed reservation on the retired asset (ordered chronologically).
- For each booking, it looks for a free substitute using the same Strategy pipeline (`SameModelStrategy` first, then `SameCategoryStrategy`).
- If a free substitute is found, the booking is moved and the student is notified by email.
- If no substitute is available, the booking is force-cancelled and the student is notified.
- Every move or force-cancel is recorded in `reassignment_log` with `incident_id = NULL` and `trigger = RETIRED` (so the admin log clearly distinguishes retirement reassignments from incident-driven ones).

## Part 2: Technical details

### Changes

| File | Change |
|---|---|
| `backend/src/main/resources/db/migration/V2026_11_19_1245__reassignment_nullable_incident_id.sql` | Flyway migration making `reassignment_log.incident_id` nullable and adding `trigger VARCHAR(32) NOT NULL DEFAULT 'INCIDENT'`. |
| `backend/src/main/java/com/campus/reassignment/domain/ReassignmentTrigger.java` | New enum (`INCIDENT`, `RETIRED`) recording why a reassignment log row exists. |
| `backend/src/main/java/com/campus/reassignment/domain/ReassignmentLog.java` | Made `incident_id` nullable and added non-null `trigger` field (`@Enumerated(EnumType.STRING)`). |
| `backend/src/main/java/com/campus/reassignment/web/ReassignmentLogResponse.java` | Exposes `trigger` alongside nullable `incidentId` on `GET /api/v1/admin/reassignments`. |
| `backend/src/main/java/com/campus/reassignment/service/LocalAssetRetiredListener.java` | `@Async` + `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` listener for `com.campus.catalog.api.AssetRetiredEvent` (always local; no `app.messaging.sqs.enabled` condition). |
| `backend/src/main/java/com/campus/reassignment/service/ReassignmentBookingProcessor.java` | Added `processSingleBooking(AssetRetiredEvent, ReservationSummary, AssetSummary)` sharing the isolated `REQUIRES_NEW` strategy + fallback loop with `incidentId = null` and `trigger = RETIRED`. |
| `backend/src/main/java/com/campus/reassignment/service/ReassignmentService.java` | Added `handle(AssetRetiredEvent)` sharing `processAssetUnavailable(...)` with `handle(AssetDamagedEvent)` for chronological upcoming-booking processing, per-booking error isolation, and notifications. |

### Verification

| Test | Scope |
|---|---|
| `LocalAssetRetiredListenerTest` | Verifies delegation to `ReassignmentService.handle(AssetRetiredEvent)` and `@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)` annotations. |
| `ReassignmentServiceTest` | Unit tests for `handle(AssetRetiredEvent)` and `getLogs` covering `trigger`, `incidentId == null`, and retirement notification text. |
| `AdminReassignmentControllerTest` | Verifies `GET /api/v1/admin/reassignments` serializes `trigger` in `PageResponse<ReassignmentLogResponse>`. |
| `AssetRetiredReassignmentIT` | Full `@SpringBootTest` on PostgreSQL with relative `Clock` dates verifying both direct `handle(AssetRetiredEvent)` (`REASSIGNED` + `FORCE_CANCELLED`) and end-to-end `POST /api/v1/admin/assets/{id}/retire` → `LocalAssetRetiredListener` wiring with `incident_id = NULL` and `trigger = RETIRED`. |

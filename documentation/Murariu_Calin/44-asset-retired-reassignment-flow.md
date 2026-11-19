# 44 — Asset Retirement Reassignment Flow (#245)

## Part 1: In simple terms

When an administrator retires (decommissions) a piece of lab equipment, students may still have upcoming confirmed bookings on it. Previously, those bookings stayed attached to the retired asset.

Now, when Liviu's catalog service retires an asset and publishes `AssetRetiredEvent`, the reassignment engine automatically reacts after the transaction commits:
- It finds every upcoming confirmed reservation on the retired asset (ordered chronologically).
- For each booking, it looks for a free substitute using the same Strategy pipeline (`SameModelStrategy` first, then `SameCategoryStrategy`).
- If a free substitute is found, the booking is moved and the student is notified by email.
- If no substitute is available, the booking is force-cancelled and the student is notified.
- Every move or force-cancel is recorded in `reassignment_log` with `incident_id = NULL` (since retirement is an administrative action, not an incident report).

## Part 2: Technical details

### Changes

| File | Change |
|---|---|
| `backend/src/main/resources/db/migration/V2026_11_19_1245__reassignment_nullable_incident_id.sql` | Flyway migration executing `ALTER TABLE reassignment_log ALTER COLUMN incident_id DROP NOT NULL;`. |
| `backend/src/main/java/com/campus/reassignment/domain/ReassignmentLog.java` | Made `incident_id` nullable on the entity and constructor. |
| `backend/src/main/java/com/campus/reassignment/service/LocalAssetRetiredListener.java` | `@Async` + `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` listener for `com.campus.catalog.api.AssetRetiredEvent` delegating to `ReassignmentService.handle(AssetRetiredEvent)`. |
| `backend/src/main/java/com/campus/reassignment/service/ReassignmentBookingProcessor.java` | Added `processSingleBooking(AssetRetiredEvent, ReservationSummary, AssetSummary)` sharing the isolated `REQUIRES_NEW` strategy + fallback loop with `incidentId = null`. |
| `backend/src/main/java/com/campus/reassignment/service/ReassignmentService.java` | Added `handle(AssetRetiredEvent)` with chronological upcoming-booking processing, per-booking error isolation, and retirement-specific student notifications. |

### Verification

| Test | Scope |
|---|---|
| `LocalAssetRetiredListenerTest` | Verifies delegation to `ReassignmentService.handle(AssetRetiredEvent)` and `@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)` annotations. |
| `ReassignmentServiceTest` | Unit tests for `handle(AssetRetiredEvent)` covering both substitute reassignment and force-cancel fallback with `incidentId == null` and retirement notification text. |
| `AssetRetiredReassignmentIT` | Full `@SpringBootTest` on PostgreSQL verifying the Flyway migration allows `incident_id = NULL` in `reassignment_log` for both `REASSIGNED` (same-model substitute) and `FORCE_CANCELLED` outcomes. |

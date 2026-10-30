# Module: Auto-Reassignment Engine

**Owner:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Base Package:** `com.campus.reassignment`  

---

## 1. Purpose

The `reassignment` module provides automated disaster recovery for lab bookings when equipment becomes damaged, unusable, or enters maintenance. Instead of requiring manual administrative intervention, the engine automatically:
1. Detects asset damage events.
2. Identifies all upcoming confirmed reservations affected by the broken asset.
3. Finds substitute assets of identical make and model that are free during the required time slots.
4. Moves reservations to the new substitute assets, or force-cancels them if no substitutes are available.
5. Writes an immutable audit trail to `reassignment_log`.

---

## 2. Design Patterns Applied

### A. Observer Pattern (`LocalAssetDamagedListener`)
- The incident reporting module publishes `AssetDamagedEvent` when equipment damage is confirmed.
- `LocalAssetDamagedListener` observes the event with `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`.
- If the incident transaction fails or rolls back, the listener is never invoked, preventing ghost reassignments.

### B. Strategy Pattern (`SubstituteStrategy`)
- Encapsulates substitution algorithms behind the `SubstituteStrategy` interface.
- Ordered implementations injected as `List<SubstituteStrategy>`:
  1. `SameModelStrategy` (`@Order(1)`): selects an identical model substitute, prioritizing equipment located in the same lab room.
  2. `SameCategoryStrategy` (`@Order(2)`): selects an alternative substitute from the same equipment category, prioritizing the same lab room.
- Enables adding new strategies in the future without changing the core engine.

---

## 3. Workflow & Data Flow

```
[ Incident Module ]
       │
       ▼ (Publishes AssetDamagedEvent)
LocalAssetDamagedListener (@TransactionalEventListener, AFTER_COMMIT, @Async)
       │
       ▼
ReassignmentService (@Transactional, REQUIRES_NEW)
  ├── 1. CatalogApi.getAsset(brokenAssetId)
  ├── 2. ReservationApi.findUpcomingActiveByAsset(brokenAssetId, now)
  └── 3. For each reservation (sorted chronologically):
           ├── For each SubstituteStrategy (ordered):
           │     ├── strategy.findSubstitute(r, damaged)
           │     └── IF substitute found:
           │           ├── ReservationApi.moveToAsset(r.id(), substitute.id(), reason)
           │           └── ReassignmentLogRepository.save(REASSIGNED)
           │
           └── IF no substitute across all strategies:
                 ├── ReservationApi.forceCancel(r.id(), reason)
                 └── ReassignmentLogRepository.save(FORCE_CANCELLED)
```

---

## 4. Transaction Isolation (`Propagation.REQUIRES_NEW`)

`ReassignmentService.handle(AssetDamagedEvent)` is annotated with `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
- Reassignment runs inside its own dedicated transaction, independent from the incident reporting transaction.
- If one booking reassignment encounters an unexpected issue, the incident report itself remains safely committed.

---

## 5. How to Test

```bash
cd backend
./gradlew test --tests "com.campus.reassignment.service.*"
```

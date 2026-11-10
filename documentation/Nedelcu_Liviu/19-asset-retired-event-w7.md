# 19. AssetRetiredEvent Domain Event for W7 Decommissioning Flow

**Author:** Liviu Nedelcu (`@NliviuN`)  
**Scope:** `catalog.api`, `catalog.service`  
**Reference:** Refs #245 (W7 Retire flow agreement)

---

## Part 1: In simple terms

When lab equipment becomes obsolete, permanently damaged, or is decommissioned by university administrators, an admin marks the item as **RETIRED** via the admin inventory interface.

However, students may already have upcoming confirmed bookings for that physical item in the days or weeks ahead. Previously, retiring an asset did not notify the reservation or reassignment engines, leaving future bookings on a retired asset until someone noticed.

With this change:
1. When an administrator retires an asset, the catalog module publishes a Spring domain event named `AssetRetiredEvent`.
2. This event cleanly announces to the system that the asset has been decommissioned, who retired it, and at what exact timestamp.
3. In Week 7, Călin's auto-reassignment engine (`reassignment` module) listens to this event after the transaction commits, automatically finding replacement hardware of the same model (`SameModelStrategy`) or gracefully force-cancelling the booking with an email notification if no units are available.

This keeps the modules decoupled: the `catalog` module does not know or care how reassignment works—it only broadcasts the event contract that other modules can react to.

---

## Part 2: Technical details

### 1. Domain Event Contract

Located in `backend/src/main/java/com/campus/catalog/api/AssetRetiredEvent.java`:

```java
package com.campus.catalog.api;

import java.time.LocalDateTime;

/**
 * Domain event published when an equipment asset is marked as RETIRED by an administrator.
 *
 * <p>Consumed by the reassignment module (Calin's engine) to reassign upcoming bookings
 * of the decommissioned asset to an available substitute of the same model, or force-cancel
 * them if no replacement is available (#245).
 *
 * @param assetId   the ID of the retired asset
 * @param retiredAt the timestamp when the asset was retired (campus local time)
 * @param actorId   the user ID of the administrator who retired the asset
 */
public record AssetRetiredEvent(Long assetId, LocalDateTime retiredAt, Long actorId) {}
```

### 2. Service Layer Integration

In `AssetService.java`:
- Injected `ApplicationEventPublisher eventPublisher`.
- Inside the `@Transactional AssetResponse retire(Long id)` method:
  1. Row lock acquired via `findByIdForUpdate(id)` (preventing race condition with concurrent student bookings).
  2. Status transitioned to `AssetStatus.RETIRED`.
  3. Row saved via `assetRepository.saveAndFlush(asset)`.
  4. Audit entry recorded via `auditService.record(currentUser.id(), "ASSET_RETIRED", ...)`.
  5. Event published: `eventPublisher.publishEvent(new AssetRetiredEvent(saved.getId(), retiredAt, currentUser.id()));`.

### 3. Unit Tests

In `AssetServiceTest.java`:
- Verified that `retireMarksAssetRetiredAndRecordsAudit()` asserts `eventPublisher.publishEvent(new AssetRetiredEvent(null, EXPECTED_NOW, 3L))`.
- Verified that failure cases (`retireThrowsNotFoundWhenAssetMissing`, `retireRejectsAlreadyRetiredAsset`) never publish the event (`verify(eventPublisher, never()).publishEvent(any())`).

### 4. Architectural Boundaries

- Follows the **Observer pattern**.
- `catalog` remains completely independent of `reservation` and `reassignment` (no outgoing dependencies).
- ArchUnit tests (`ModuleBoundaryArchitectureTest`) pass cleanly with 0 cycle or boundary violations.

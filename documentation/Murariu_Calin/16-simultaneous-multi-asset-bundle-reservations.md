# 16. Simultaneous Multi-Asset Bundle Reservations

**Author:** Murariu Călin (Member 3 - Reservations & Reassignment)  
**Pull Request:** [#212](https://github.com/FundamenteleInginerieiSoftware/campus-hub/pull/212)  

---

## Part 1: In simple terms

In specialized engineering laboratories, experiments rarely require just a single tool. A student often needs a synchronized kit—for example, a digital multimeter, a power supply, and an oscilloscope—for the exact same time slot:

1. **Atomic All-or-Nothing Guarantee:**  
   If a student books a 3-device kit for a lab session, having 2 out of 3 devices is useless if the essential oscilloscope is missing. The system evaluates all requested equipment simultaneously in a single transaction: either all equipment is confirmed together, or nothing is booked.
2. **Deadlock Prevention under Heavy Concurrency:**  
   If Student A tries to reserve (Oscilloscope 1, Power Supply 2) while Student B simultaneously tries to reserve (Power Supply 2, Oscilloscope 1), traditional databases risk getting stuck in a deadlock where each student holds one lock and waits for the other. By sorting asset IDs in strict numerical order before locking, our engine completely eliminates circular waits.
3. **Collective Management as a Kit:**  
   All individual bookings created within a multi-asset request are tied together via a unique `bundle_id` (UUID). The user can view the complete kit at once or cancel all items in the kit with a single click.

---

## Part 2: Technical details

### 1. Database Schema & Flyway Migration

A nullable column `bundle_id` with an index was introduced to the `reservations` table via Flyway migration `V2026_11_08_1400__reservation_add_bundle_group.sql`:

```sql
ALTER TABLE reservations ADD COLUMN bundle_id UUID;
CREATE INDEX idx_reservations_bundle_id ON reservations(bundle_id) WHERE bundle_id IS NOT NULL;
```

---

### 2. Deadlock Avoidance Algorithm

When locking multiple resources concurrently, deadlocks occur if transactions acquire locks in different orders:

```java
// Sort asset IDs ascending to prevent distributed deadlocks
List<Long> sortedAssetIds = req.assetIds().stream().sorted().toList();

// Acquire pessimistic row locks in deterministic order
for (Long assetId : sortedAssetIds) {
    catalogApi.lockForBooking(assetId);
}
```

By ensuring every transaction acquires locks in strictly increasing order (`id1 < id2 < ...`), circular wait conditions are mathematically impossible (Coffman condition 4 broken).

---

### 3. Business Rules Matrix

| Rule | Value / Check | Error Code | HTTP Status |
|---|---|---|---|
| **Bundle Size** | Between 2 and 5 items (`2 <= size <= 5`) | `INVALID_BUNDLE_SIZE` | `409 Conflict` / `400 Bad Request` |
| **All-or-Nothing Availability** | All assets must be bookable and free of overlap | `RESERVATION_CONFLICT` / `ASSET_NOT_BOOKABLE` | `409 Conflict` |
| **Quota Enforcement** | Total active reservations + bundle size <= 2 (bypassed by admin) | `RESERVATION_LIMIT_REACHED` | `409 Conflict` |
| **Time Alignment** | Mon-Fri 08:00–20:00, :00/:30 alignment, duration 30m–4h, horizon <= 14d | Standard slot error codes | `409 Conflict` |
| **Kit Cancellation** | Cancels all active items in the bundle | `200 OK` | `List<ReservationResponse>` |

---

### 4. REST Endpoints

| Method | Endpoint | Request Body | Description |
|---|---|---|---|
| `POST` | `/api/v1/reservations/bundle` | `CreateMultiAssetReservationRequest` | Reserve 2..5 assets simultaneously |
| `GET` | `/api/v1/reservations/bundle/{bundleId}` | – | Retrieve all reservations in a bundle |
| `POST` | `/api/v1/reservations/bundle/{bundleId}/cancel` | optional `?reason=...` | Cancel all reservations in a bundle |

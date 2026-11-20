# 47 — Source-Asset Guard on Reassignment Move & Force-Cancel (#319)

## Part 1: In simple terms

If an equipment unit suffers a serious incident and an admin retires it at the same time (or two serious incidents are reported back-to-back), two reassignment handlers can both read the same upcoming booking before either finishes moving it.

Previously, after Handler 1 moved booking `B` from asset `A` to substitute `S1`, Handler 2 still tried to move (or force-cancel) `B` because `B` was still `CONFIRMED`. That could create a duplicate `reassignment_log` row and send a second email to the student.

Now, both `moveToAsset` and `forceCancel` take a row lock on the reservation and check that it is **still assigned to the triggering asset** (`expectedSourceAssetId`). If another handler already moved it, the second handler sees the new `assetId`, returns `false`, and skips logging and emailing cleanly.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `ReservationApi.java` | Added `moveToAsset(reservationId, expectedSourceAssetId, newAssetId, reason)` and `forceCancel(reservationId, expectedSourceAssetId, reason)`; kept the existing 3-arg / 2-arg methods as `default` delegates passing `null` so no existing caller breaks. |
| `ReservationService.java` | Switched `moveToAsset` and `forceCancel` to `reservationRepository.findByIdForUpdate(reservationId)` (preserving the `reservation -> asset` lock order from #270) and added the `expectedSourceAssetId != null && !expectedSourceAssetId.equals(r.getAssetId())` early-return guard. |
| `ReassignmentBookingProcessor.java` | Passes `sourceAssetId` into `reservationApi.moveToAsset(...)` and `reservationApi.forceCancel(...)`. |
| `ReservationLifecycleService.java` | Passes `overdueAssetId` into `reservationService.moveToAsset(...)` in `tryPreventiveMove`. |
| Tests | Added mismatch unit tests in `ReservationServiceTest`, updated processor/service/lifecycle unit tests, and added `AssetRetiredReassignmentIT.staleConcurrentHandlerAfterMoveDoesNotLogDuplicateMoveOrForceCancel()` verifying a single `reassignment_log` row on PostgreSQL. |

# 34. Same-Model Substitute Suggestions on Booking Conflict

**Author:** Călin Murariu (@Swiorx)  
**Date:** 2026-11-15  
**Branch:** `feat/reservation-same-model-substitutes`  
**Closes:** #285

---

## Part 1: In simple terms

Before this change, if a student opened the booking calendar for a specific oscilloscope or multimeter and picked a time slot that was already booked (or if that unit was under maintenance), the panel only showed a red "Slot is not available" warning. Even if an identical unit of the exact same model was sitting free in the same lab room at that same hour, the student had to leave the booking page, search the catalog, open each sister unit one by one, and re-pick their date and time.

Now, when `GET /api/v1/reservations/availability?assetId=...` sees that the chosen unit is busy (`RESERVATION_CONFLICT`) or unavailable (`ASSET_NOT_BOOKABLE`), it automatically checks for free `AVAILABLE` units of the **same model** (ignoring busy or temporarily held units) and returns their IDs in `availableAssetIds`, ordered **same lab room first**, then lowest asset ID. On the booking calendar, up to 3 free identical units appear directly below the red conflict alert with a **"Same room"** badge and a **"Switch unit"** button. Clicking **"Switch unit"** switches the active unit inside `CalendarBookingView` in place while keeping the student's selected day, start time, and duration intact.

---

## Part 2: Technical details

### Backend (`com.campus.reservation`)
1. **`AvailabilityResponse`** (`backend/src/main/java/com/campus/reservation/web/AvailabilityResponse.java`):
   - Added a 6-argument `forAsset(assetId, start, end, available, conflictReason, availableAssetIds)` factory overload alongside the existing 5-argument signature (which delegates to it) so single-asset checks keep the exact existing JSON contract (`available: false`, `availableAssetIds: [...]`, `conflictReason: "RESERVATION_CONFLICT" | "ASSET_NOT_BOOKABLE"`).
2. **`ReservationService.checkAvailability` & `findFreeSameModelSubstitutes`** (`backend/src/main/java/com/campus/reservation/service/ReservationService.java`):
   - Preserved the short-circuit on blank/null `model` and skipped `findBusyAssetIds` when `catalogApi.findAvailableByModel(asset.model(), asset.id())` returns no candidates.
   - Filtered candidates against `findBusyAssetIds(start, end)` (which includes both active `CONFIRMED`/`CHECKED_OUT`/`OVERDUE` reservations and active non-expired holds from other students).
   - Sorted candidates with `!Objects.equals(candidate.labRoomId(), asset.labRoomId())` ascending (so `true` same-room matches come first), then `AssetSummary::id` ascending—matching the reassignment engine's `SameModelStrategy` deterministic tie-breaking.
3. **Tests**:
   - `AvailabilityResponseTest#forAssetUnavailableWithSubstitutesPopulatesIds`: verifies the new `forAsset` factory preserves substitute IDs.
   - `ReservationServiceTest#checkAvailabilitySuggestsFreeSameModelSubstitutesSortedBySameRoomFirst`: verifies `RESERVATION_CONFLICT` excludes booked substitutes and sorts same-room units before other rooms.
   - `ReservationServiceTest#checkAvailabilitySuggestsFreeSameModelSubstitutesWhenUnderMaintenance`: verifies `ASSET_NOT_BOOKABLE` excludes actively held substitutes and returns remaining free same-model units.

### Frontend (`frontend/src/components/CalendarBookingView.tsx`)
1. Added `switchedAsset` local override state keyed by `initialAsset.id` so switching units in-place keeps `selectedDay`, `selectedStartTime`, and `durationMinutes` untouched, while automatically resetting if a new parent prop asset is opened.
2. When `!availability.checking && !availability.data.available` with non-empty `availableAssetIds`, fetches up to 3 substitute `AssetSummary` records via `fetchAssetSummary` and renders them under the availability alert with a `Same room` badge (when `sub.labRoomId === asset.labRoomId`) and a `Switch unit` action (plus a `Back to <original unit>` button once switched).

### Verification
- `cd backend && ./gradlew spotlessApply build`
- `cd frontend && npm run lint && npm run build`

# 63. Reservation Backend Integrity, Boundary Rules & Polish Fixes (#362 PR 2)

**Author:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Issue:** Refs #362 (§3 Medium backend, §4 Low/Polish backend, §5 Optional backend)  
**Modules:** `reservation/`, `reassignment/`

---

## Part 1: In simple terms

Following the concurrency & lock-DAG fixes in PR #370, this PR closes the remaining backend edge cases discovered during live testing:

1. **Duplicate equipment in a kit (`bookBundle`):** Rejects duplicate asset IDs right away instead of attempting to double-book the same unit inside one bundle.
2. **Disable → immediate re-enable safety:** If an admin disables an account by mistake and immediately re-enables it before the reservation cleanup listener runs, the listener checks whether the account is active again under the user quota lock and skips cancelling their bookings.
3. **30-minute slot check-out boundary:** A 30-minute booking (`10:00–10:30`) can no longer be checked out at exact `10:30:00` (when the slot has already ended), while checking out at `10:29:59` still succeeds.
4. **Calendar link double-click:** Generating a private iCalendar link now uses an atomic database upsert so double-clicking "Generate link" never fails with a 500 error.
5. **Consistency & polish:** Retired assets return `404 ASSET_NOT_FOUND` to students on `/availability` (matching catalog details and next-free-slots); hold confirmation expires at exact `now == expiresAt`; extension checks the 14-day horizon against slot start; cancelling a bundle with 0 `CONFIRMED` items returns `409 CANCELLATION_NOT_ALLOWED`; paginated queries use deterministic `id` tie-breakers; Swagger docs show `08:00-20:00`; a partial index speeds up equipment reliability usage queries; and `ReservationHold` drops its class-level `@Setter`.

---

## Part 2: Technical details

### 1. Medium Backend Fixes (#362 §3)

| Fix | Location | Behavior |
|---|---|---|
| Duplicate bundle IDs | `ReservationService.validateBundleAssetIds` | Rejects `Set.copyOf(assetIds).size() != assetIds.size()` with `409 DUPLICATE_ASSET_IN_BUNDLE` (`BusinessRuleException`). |
| Disable → re-enable race | `UserDisabledReservationListener.onUserDisabled` | Inside `REQUIRES_NEW`, acquires `reservationRepository.lockUserQuota(userId)` and checks `userApi.findActiveRole(userId).isPresent()`; skips cancellation when active. |
| 30-min check-out at `endTime` | `ReservationService.checkOutReservation` | Rejects when `!now.isBefore(reservation.getEndTime())` alongside `[start - 15m, start + 30m]` (`409 CHECKOUT_WINDOW_CLOSED`). |
| Double-click calendar link 500 | `CalendarFeedRepository.upsertToken` + `CalendarTokenService.create` | Native PostgreSQL `INSERT INTO calendar_feeds ... ON CONFLICT (user_id) DO UPDATE SET token_hash = EXCLUDED.token_hash, created_at = EXCLUDED.created_at`. |

### 2. Low / Polish & Optional Backend Fixes (#362 §4 & §5)

- **Retired asset 404 consistency:** `ReservationService.checkAvailability` throws `404 ASSET_NOT_FOUND` for `AssetStatus.RETIRED` when `!currentUser.isAdmin()` (admins keep `ASSET_NOT_BOOKABLE`).
- **Hold exact expiry second:** `ReservationHold.confirm(now)` changed from `now.isAfter(expiresAt)` to `!now.isBefore(expiresAt)`, matching `existsActiveOverlap` (`expiresAt > now`) and `expire(now)` (`!now.isBefore(expiresAt)`).
- **Extension 14-day horizon:** `ReservationSlotValidator.validateExtensionRules` checks `validateHorizon(start, now)` instead of `validateHorizon(newEnd, now)` to match `validateSlotRules`.
- **Empty bundle cancel:** `ReservationService.cancelBundle` tracks `cancelledCount` and throws `409 CANCELLATION_NOT_ALLOWED` if `cancelledCount == 0`.
- **Deterministic pagination & Swagger:** Added `IdAsc` tie-breakers to `ReservationRepository.findByUserId*OrderByStartTimeAscIdAsc`, `IdDesc` to `ReassignmentLogRepository.findAllByOrderByCreatedAtDescIdDesc`, and fixed `09:00-20:00` → `08:00-20:00` in `ReservationController`.
- **Partial index & entity encapsulation (#362 §5):** Added Flyway migration `V2026_11_27_1400__reservation_add_checked_out_at_index.sql` (`CREATE INDEX idx_reservations_checked_out_at ON reservations (checked_out_at) WHERE checked_out_at IS NOT NULL`) and removed class-level `@Setter` on `ReservationHold`.

### 3. Verification

- **Unit & repository tests:** `ReservationServiceTest`, `ReservationHoldTest`, `UserDisabledReservationListenerTest`, `CalendarTokenServiceTest`, `CalendarFeedRepositoryTest`, `ReservationServiceRulesTest`, `ReassignmentServiceTest`.
- **PostgreSQL 18 integration tests:**
  - `UserDisabledReservationListenerIT.reEnabledUserBeforeListenerRunsKeepsUpcomingBookingConfirmed`
  - `ReservationConcurrencyIT.concurrentCalendarTokenCreateNeverThrowsUniqueViolation`
  - `ReservationConcurrencyIT.cancelBundleWaitsBehindCancelUpcomingAndCompletesWithoutStaleVersion` (asserts `409 CANCELLATION_NOT_ALLOWED`)
- **Build:** `cd backend && ./gradlew spotlessApply build` — `BUILD SUCCESSFUL`.

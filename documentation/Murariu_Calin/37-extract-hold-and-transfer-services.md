# 37. Extract HoldService & TransferService, Unify Slot Validation, and Simplify ReservationService Constructors

**Author:** Călin Murariu (@Swiorx)  
**Date:** 2026-11-16  
**Branch:** `refactor/reservation-extract-hold-and-transfer-services`  
**Refs:** #267 (Section 2)

---

## Part 1: In simple terms

As new features were added to the reservation module (1-minute slot holds, peer-to-peer transfers, multi-asset bundles, and duration extensions), `ReservationService` grew to 1,850 lines and carried six different constructors with `Optional` and `null` checks left over from older unit tests. At the same time, the sequence of checks that verifies a slot is free ("lock the asset -> check it is bookable -> check time rules -> check existing bookings -> check other students' holds") was written separately in single bookings, bundle bookings, hold creation, and hold confirmation.

This refactor splits those responsibilities cleanly without changing any API route, status code, or business rule:
- **`HoldService`** now owns all 1-minute hold operations (acquire, confirm, release, list active holds, and expired hold sweep).
- **`TransferService`** now owns all peer-to-peer transfer operations (initiate, accept, reject, cancel, list incoming pending transfers, and auto-cancelling pending offers when a booking is cancelled, extended, checked out, marked no-show, or reassigned).
- **`ReservationSlotValidator`** centralizes the shared slot timing rules and slot-free check so single bookings, bundles, and holds can never drift out of sync.
- **`ReservationService`** is reduced to a single required-dependency constructor (`@RequiredArgsConstructor`), removing all `Optional<>` wrappers and defensive `!= null` bean checks.

---

## Part 2: Technical details

### Backend (`com.campus.reservation.service` & `com.campus.reservation.web`)

| File | Change |
|---|---|
| `HoldService.java` | Extracted `createHold`, `confirmHold`, `releaseHold`, `getMyActiveHolds`, and `sweepExpiredHolds` from `ReservationService` using `@RequiredArgsConstructor`. |
| `TransferService.java` | Extracted `initiateTransfer`, `getMyPendingTransfers`, `acceptTransfer`, `rejectTransfer`, `cancelTransfer`, and package-private `cancelPendingTransferIfExists`. |
| `ReservationSlotValidator.java` | Package-private helper unifying `validateSlotRules`, `validateExtensionRules`, `lockAndValidateSlotFree`, `ensureBookable`, `ensureNoSlotConflict`, and `ensureNoActiveHoldOverlap`. |
| `ReservationServiceSupport.java` | Package-private helper sharing `truncatedNow`, `notifyUserAfterCommit`, `loadOwnedOrAdmin`, `loadOwnedOrAdminForUpdate`, `isCurrentUserAdmin`, `currentUserIdOrNull`, and `validatePagination`. |
| `ReservationService.java` | Replaced the 6 constructors and `Optional<>` fields with a single `@RequiredArgsConstructor` over 9 non-null dependencies (`reservationRepository`, `catalogApi`, `currentUser`, `auditService`, `notificationService`, `clock`, `transferService`, `userApi`, `reservationHoldRepository`); removed ~20 bean null checks; delegated hold/transfer cascade and slot validation. |
| `ReservationHoldController.java` | Switched injection from `ReservationService` to `HoldService`. |
| `ReservationController.java` | Added `TransferService` injection for the 5 `/transfers/**` endpoints. |
| `ReservationLifecycleService.java` | Added `HoldService` injection for `sweepExpiredHolds(now)` in `runLifecycleSweep()` and `sweepExpiredHolds()`. |

### Tests

| Test Class | Update |
|---|---|
| `ReservationHoldServiceTest` | Wired directly against `HoldService` (8-arg constructor with `UserApi` mock) covering hold creation, cooldown, limits, confirm, IDOR, release, active list, and expiry sweep. |
| `TransferServiceTest` | Dedicated unit test class for `TransferService` (`initiateTransfer`, `acceptTransfer`, `rejectTransfer`, `cancelTransfer`, `getMyPendingTransfers`, anti-probing, bundle guard, 24h offer limit, quota enforcement). |
| `ReservationServiceTest` | Updated setup to construct `TransferService` and pass all 9 required dependencies to `ReservationService`; kept reservation lifecycle, bundle, extension, availability, and transfer-cascade tests. |
| `ReservationServiceRulesTest` & `ReservationAdminSearchTest` | Updated `new ReservationService(...)` calls to pass all 9 required dependencies. |
| `ReservationControllerTest` & `ReservationHoldControllerTest` | Updated `@WebMvcTest` mocks to mock `TransferService` and `HoldService` respectively. |
| `ReservationLifecycleServiceTest` & `ReservationLifecycleFailureTest` | Updated `ReservationLifecycleService` constructor calls to pass `@Mock HoldService`. |

### Verification
- `cd backend && ./gradlew.bat spotlessApply compileTestJava`

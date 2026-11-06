# 14. Swagger Examples & Mutation Test Gaps

**Author:** Murariu Călin (Member 3 - Reservations & Reassignment)  
**Pull Request:** [#191](https://github.com/FundamenteleInginerieiSoftware/campus-hub/pull/191)  
**Related Issues:** [#188](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/188) (Follow-up of #180, #181, #184)  

---

## Part 1: In simple terms

When building software for university labs, it is not enough for code to "pass" happy-path tests:
1. **Interactive Documentation (Swagger / OpenAPI):** Anyone testing the booking system in their browser should be able to click "Try it out" and execute a valid reservation immediately. Previously, Swagger defaulted to random ISO timestamps like `2026-11-05T13:26:06.320Z`, which automatically failed our university rules (not aligned to :00/:30 minutes, or scheduled in the past). We provided realistic, pre-aligned example timestamps so professors and teammates can test the endpoints in one click.
2. **Database Test Cleanliness:** One of our integration tests (`ReassignmentRollbackIT`) inserted test records with a hardcoded user ID (`100`). On local laptops with persistent databases, once normal student registrations reached ID 100, the database rejected new users with an "Email already taken" error due to primary key collisions. We added an automatic cleanup after each test so tests leave a spotless database.
3. **Mutation Testing (PIT):** Normal test coverage (JaCoCo) only measures which lines were *executed*, not whether the test actually verified the result. Mutation testing deliberately breaks small pieces of code (changes `<` to `<=`, removes a check, or replaces a return with `null`) and checks if our tests catch the change. We added tests to kill all surviving mutants in the reservation and reassignment modules, ensuring our tests actually verify business invariants.

---

## Part 2: Technical details

### 1. Swagger / OpenAPI Annotations & Error Codes

| Component | Annotation / Class | Purpose |
|---|---|---|
| `CreateReservationRequest` | `@Schema(example = "1")` on `assetId`, `@Schema(example = "2026-11-10T14:00:00")` on `startTime`, `@Schema(example = "2026-11-10T16:00:00")` on `endTime` | Provides slot-aligned future weekday times, preventing `SLOT_NOT_ALIGNED` or `BOOKING_IN_PAST` errors on interactive Swagger execution. |
| `ReservationController.checkAvailability` | `@Parameter(example = "1")` on `assetId`/`categoryId`, `@Parameter(example = "2026-11-10T14:00:00")` on `startTime` and `endTime` | Aligns query parameters with reservation slot boundaries and valid default IDs. |
| `ReservationController` | `@ApiResponse(responseCode = "400", description = "... INVALID_PARAMETER, MALFORMED_REQUEST")` | Documents RFC 9457 `ProblemDetail` responses for invalid request payloads and query parameters (availability query params return `INVALID_PARAMETER` on mismatch/missing). |
| `AdminReservationController` & `AdminReassignmentController` | `@ApiResponse(responseCode = "400", description = "... VALIDATION_FAILED ...")` | Documents query parameter validation on pagination bounds (`page >= 0`, `1 <= size <= 100`). |
| `ReservationOpenApiCustomizer` | Implements `OpenApiCustomizer` | Dynamically updates request body and query parameter examples to the next upcoming weekday (14:00-16:00) using the shared `Clock`, ensuring Swagger "Try it out" remains valid indefinitely for M1 demos and defense without manual date adjustments. |

### 2. Integration Test Isolation (`ReassignmentRollbackIT`)

- **Root Cause:** `ReassignmentRollbackIT` seeded fixed rows with `users.id = 100`, `lab_rooms.id = 500`, and `categories.id = 500` without tearing them down in `@AfterEach`. When PostgreSQL identity sequence `users_id_seq` eventually reached `100` on persistent dev databases (`campus_test`), subsequent registration attempts hit duplicate key conflicts (`users_pkey`), causing intermittent `409 EMAIL_TAKEN` failures in unrelated test suites (such as `SecurityPassIT`).
- **Fix:** Added comprehensive `@AfterEach` teardown deleting test rows in foreign-key order:
  ```java
  reassignmentLogRepository.deleteAll();
  reservationRepository.deleteAll();
  jdbcTemplate.update(
          "DELETE FROM assets WHERE id IN (?, ?, ?, ?)",
          DAMAGED_ASSET_ID,
          SUB_MODEL_BUSY_ID,
          SUB_CAT_FREE_ID,
          SUB_MODEL_SECOND_FREE_ID);
  jdbcTemplate.update("DELETE FROM lab_rooms WHERE id = 500");
  jdbcTemplate.update("DELETE FROM categories WHERE id = 500");
  jdbcTemplate.update("DELETE FROM users WHERE id = 100");
  ```

### 3. PIT Mutation Survivors Eliminated

| Target Class | Mutant Killed | Added Test Verification |
|---|---|---|
| `ReassignmentService.now()` | Return value replaced with `null` survived because tests matched time with `any()`. | Pinned `eq(FIXED_NOW)` in `findUpcomingActiveByAsset` across all unit tests in `ReassignmentServiceTest`. |
| `SameModelStrategy` & `SameCategoryStrategy` | Filter predicate replaced with `true` survived because candidates were always `AVAILABLE`. | Added `strictlyExcludesUnderMaintenanceCandidates` and `strictlyExcludesRetiredCandidates`, verifying that non-available assets are filtered out before `isAssetFree` is called. |
| `ReservationLifecycleService.tryPreventiveMove` | Return boolean replaced with `true` in catch block was unreached (`NO_COVERAGE`). | Added `preventiveMoveSkipsWhenMoveToAssetThrowsRuntimeException` verifying catch block handles `RuntimeException` and returns 0 reassignments. |
| `ReservationService.moveToAsset` & `forceCancel` | Boolean return replaced with `false` survived without assertions. | Added `assertThat(result).isTrue()` assertions. |
| `ReservationService.markNoShow` & `markOverdue` | Call to `AuditService.record` was removed without failure. | Added Mockito `verify(auditService).record(...)` checking `AUTO_NO_SHOW` and `MARK_OVERDUE` event logging. |
| `ReservationService.checkAvailability` | Validation call removed survived without invalid parameter test. | Added `checkAvailabilityValidatesSlotRules` asserting `BusinessRuleException` on invalid time ranges. |
| `ReservationService.loadOwnedOrAdmin` & `returnReservation` | Return value replaced with `null` survived when reservation not found. | Added `getByIdNotFound()` and `returnReservationNotFound()` asserting `404 RESERVATION_NOT_FOUND`. |
| `ReservationService.findBusyAssetIds` | Return value replaced with empty list survived without unit test. | Added `findBusyAssetIdsDelegates()` verifying repository delegation with `ReservationStatus.ACTIVE_STATUSES`. |


> [!NOTE]
> **Admin Search Specification Coverage:** The `ReservationSpecification` dynamic query predicates are thoroughly covered against real PostgreSQL queries in `ReservationAdminSearchTest` (`@DataJpaTest`, PR #172), which verifies status, userId, assetId, date filtering, and ascending/descending order.

---

## Verification

- `./gradlew spotlessApply build` — 476 tests passing, Spotless formatting verified, JaCoCo coverage maintained.
- `./gradlew pitest` — Mutation testing enabled on-demand (#180); surviving mutation gaps closed across `reservation` and `reassignment` packages.

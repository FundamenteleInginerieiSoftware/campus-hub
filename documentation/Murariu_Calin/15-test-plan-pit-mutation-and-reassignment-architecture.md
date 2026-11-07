# 15. Comprehensive Test Plan, PIT Mutation Testing & Reassignment Architecture

**Author:** Murariu Călin (Member 3 - Reservations & Reassignment, SQA Test Lead)  
**Pull Requests:** [#199](https://github.com/FundamenteleInginerieiSoftware/campus-hub/pull/199), [#201](https://github.com/FundamenteleInginerieiSoftware/campus-hub/pull/201)  
**Related Issues:** [#188](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/188) (Follow-up of #180, #181, #184)  

---

## Part 1: In simple terms

Building a reliable equipment reservation and automatic reassignment engine requires more than making code compile and run smoothly on a developer's laptop:

1. **Formalized Test Plan & SQA Strategy:**  
   Before software enters production or defense evaluation, every component must fit into a structured testing pyramid. As SQA Test Lead, I established the comprehensive team test plan defining how unit tests (Mockito), repository tests (`@DataJpaTest` with real PostgreSQL queries), API controller tests (`@WebMvcTest`), end-to-end workflows (`DemoFlowIT`), and static architecture rules (ArchUnit) work together to guarantee zero defects.

2. **Going Beyond Code Coverage with Mutation Testing (PIT):**  
   Standard test coverage tools like JaCoCo only show whether lines of code were executed during a test run, but they cannot prove that the test would fail if someone introduced a bug. PIT Mutation Testing automatically generates artificial bugs ("mutants")—such as flipping `<` to `<=`, skipping method calls, or replacing return values with `null` or empty lists—and verifies whether our test suite catches and "kills" them. We analyzed all surviving mutants in the reservation and reassignment services and eliminated every single one, achieving **100% mutation coverage** across our core services.

3. **Robust Auto-Reassignment Architecture:**  
   When equipment is reported damaged by a student or admin, the system must autonomously protect upcoming bookings without crashing. By combining the **Observer pattern** (`AssetDamagedEvent` handled after transaction commit) with the **Strategy pattern** (trying identical models first, then falling back to same-category alternatives), and wrapping each reassignment in an isolated transaction (`Propagation.REQUIRES_NEW`), the system guarantees that if moving one booking encounters a concurrency conflict, other bookings continue to be rescued or force-cancelled safely.

---

## Part 2: Technical details

### 1. SQA Test Plan & Testing Pyramid

The project testing architecture is organized into distinct, non-overlapping verification layers:

| Layer | Target | Tooling | Isolation / Speed |
|---|---|---|---|
| **Unit Tests** | Business logic, state machines, math boundaries, strategy selection | JUnit 5, AssertJ, Mockito (`@ExtendWith(MockitoExtension.class)`) | Pure Java, no Spring context, execution < 10ms per test |
| **Repository Tests** | Custom queries, pessimistic locking, JPA Specifications | `@DataJpaTest`, `@AutoConfigureTestDatabase(replace = NONE)` | Local PostgreSQL test database (`campus_test`), transactional rollback per test |
| **Slice / Controller Tests** | HTTP status codes, security filters, RFC 9457 ProblemDetail payloads | `@WebMvcTest`, `@MockitoBean`, `@WithMockUser` | Mock MVC, security filter chain enabled, fast web slice |
| **Integration / Flow Tests** | Cross-module transaction boundaries, event listeners, async triggers | `@SpringBootTest(webEnvironment = RANDOM_PORT)` | Full application context, real PostgreSQL container/database |
| **Architecture Quality Gates** | Package layering, circular dependency prevention, Clock usage | ArchUnit (`ModuleBoundaryArchitectureTest`, `CodingConventionsArchitectureTest`) | Static bytecode analysis, zero runtime overhead |
| **Mutation Testing Gates** | Verification quality, assertion depth, boundary correctness | PIT Mutation Testing (`gradle pitest`) | Bytecode mutation analysis across domain and service classes |

---

### 2. PIT Mutation Testing Survivor Elimination

During PIT mutation analysis across `com.campus.reservation.service.*` and `com.campus.reassignment.service.*`, 8 surviving mutants were identified and systematically resolved:

```
+-------------------------------------------------------------------------------+
| Package: com.campus.reservation.service                                       |
| - ReservationService:           231/231 lines (100%), 143/143 mutants (100%)  |
| - ReservationLifecycleService:  103/103 lines (100%),  25/25  mutants (100%)  |
+-------------------------------------------------------------------------------+
| Package: com.campus.reassignment.service                                      |
| - ReassignmentService:           43/43  lines (100%),  16/16  mutants (100%)  |
| - ReassignmentBookingProcessor:  50/50  lines (100%),  10/10  mutants (100%)  |
| - SameModelStrategy:             12/12  lines (100%),   9/9   mutants (100%)  |
| - SameCategoryStrategy:          12/12  lines (100%),   9/9   mutants (100%)  |
| - SubstituteStrategy:             1/1   lines (100%),   1/1   mutants (100%)  |
| - SubstituteCandidate:            3/3   lines (100%),   1/1   mutants (100%)  |
+-------------------------------------------------------------------------------+
```

#### A. Pagination Boundary Relational Operators
In `ReservationService.validatePagination` and `ReassignmentService.getLogs`, mutants altered boundary relational checks (`page < 0`, `size < 1`, `size > 100`, `page * size > Integer.MAX_VALUE`):
- Added dedicated tests in `ReservationServiceTest` and `ReassignmentServiceTest` validating exact edge conditions:
  - `page = -1` -> throws `INVALID_PARAMETER`
  - `size = 0` and `size = 101` -> throws `INVALID_PARAMETER`
  - `size = 1` and `size = 100` -> succeeds (valid boundaries)
  - `(long) page * size > Integer.MAX_VALUE` (e.g., `page = Integer.MAX_VALUE`, `size = 2`) -> throws `INVALID_PARAMETER`
  - `page * size == Integer.MAX_VALUE` -> succeeds without overflow error

#### B. Post-Commit Transaction Synchronization
In `ReservationService.cancelReservationAsAdmin`, an administrative forced cancellation registers an `afterCommit` hook on `TransactionSynchronizationManager` to send a notification to the affected student:
- Added `cancelReservationAsAdminDispatchesNotificationAfterCommit` using `TransactionSynchronizationManager.initSynchronization()`, committing the test synchronization context and asserting that `notificationService.send()` was invoked with the cancel reason.
- Added `cancelReservationAsAdminSuppressesNotificationErrors` verifying that any exception thrown by `NotificationService` during the after-commit phase is safely caught and logged without propagating or disrupting the caller.

#### C. `ReassignmentBookingProcessorTest` Comprehensive Coverage
`ReassignmentBookingProcessor` executes the reassignment logic for each individual booking within its own `TransactionTemplate` configured with `PROPAGATION_REQUIRES_NEW`:
- **Transaction Propagation Verification:** Tested `requiresNewTx` bean initialization asserting that its propagation behavior matches `TransactionDefinition.PROPAGATION_REQUIRES_NEW`.
- **Successful Move:** Verifies candidate selection, booking update via `reservationApi.moveToAsset`, reassignment audit logging in `reassignment_log`, and student notification.
- **Concurrency Conflict Retry (`SubstituteNoLongerFreeException`):** When another thread claims candidate A, `ReservationApi.moveToAsset` throws `SubstituteNoLongerFreeException`. The processor catches this exception, appends candidate A to `attemptedAssetIds`, and seamlessly advances to candidate B.
- **Cross-Strategy Deduplication:** Asserts that candidate IDs already attempted under `SameModelStrategy` are passed to `SameCategoryStrategy` to prevent duplicate attempts or redundant lock acquisition.
- **Force-Cancel Fallback:** When all strategies exhaust available candidates, the processor falls back to `reservationApi.forceCancel` with reason `NO_SUBSTITUTE_AVAILABLE`, logging `CANCELLED` status in `reassignment_log` and alerting the student.
- **Status Guard:** Verifies that if a booking was already cancelled or completed concurrently, the processor immediately skips execution without modifying records.

#### D. `SubstituteStrategy` Default Method
Added `SubstituteStrategyTest` exercising the default interface method `findSubstitute`, asserting that candidate lists are correctly converted to `Optional.of(first)` or `Optional.empty()`.

---

### 3. Reassignment Engine Architecture Deep Dive

```mermaid
flowchart TD
    A["AssetDamagedEvent (Incident Module)"] -->|@TransactionalEventListener<br/>phase = AFTER_COMMIT| B["LocalAssetDamagedListener"]
    B --> C["ReassignmentService.reassignDamagedAsset"]
    C --> D["Query Active Bookings: findUpcomingActiveByAsset"]
    D --> E["Iterate Bookings (Ascending Start Time)"]
    E --> F["ReassignmentBookingProcessor.processSingleBooking<br/>(TransactionTemplate REQUIRES_NEW)"]
    
    F --> G{"Try Strategy 1:<br/>SameModelStrategy"}
    G -->|Candidate Found| H["reservationApi.moveToAsset(bookingId, candidateId)"]
    G -->|No Candidate / Busy| I{"Try Strategy 2:<br/>SameCategoryStrategy"}
    I -->|Candidate Found| H
    I -->|All Candidates Busy / Exhausted| J["reservationApi.forceCancel(bookingId, reason)"]
    
    H -->|Success| K["Insert REASSIGNED Log & Notify Student"]
    H -->|SubstituteNoLongerFreeException| L["Add to attemptedAssetIds & Retry Next Candidate"]
    L --> G
    J --> M["Insert CANCELLED Log & Notify Student"]
```

#### Key Architecture Guarantees:
1. **Decoupled Incident Reporting:** Incident reports never wait for reassignment to complete. Reassignment runs asynchronously or after the incident transaction successfully commits.
2. **Deterministic Strategy Pipeline:** Spring injects a list of `SubstituteStrategy` ordered by `@Order`:
   - `@Order(1) SameModelStrategy`: identical model, same lab room, operational status `AVAILABLE`, not booked in target slot.
   - `@Order(2) SameCategoryStrategy`: same category, operational status `AVAILABLE`, not booked in target slot.
3. **Transaction Isolation:** Each booking runs in its own `REQUIRES_NEW` transaction. A deadlock or conflict on booking #3 cannot roll back successful reassignments of bookings #1 and #2.

---

## Verification & Quality Gates

- `./gradlew spotlessApply build` — 510+ tests pass, zero Spotless violations, build time < 25s.
- `./gradlew pitest` — 100% mutation coverage across `reservation` and `reassignment` service classes with 0 surviving mutants.
- ArchUnit (`ModuleBoundaryArchitectureTest`, `CodingConventionsArchitectureTest`) — 100% architecture rule compliance (no circular dependencies, explicit package encapsulation).

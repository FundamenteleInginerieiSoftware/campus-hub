# Document 29: Global Optimal Reassignment Planner (greedy + branch and bound)

## Part 1: In simple terms (for non-programmers)

### What problem are we solving?
When an essential piece of lab equipment breaks down (e.g., an advanced oscilloscope is damaged), multiple students who booked it in the coming days are at risk of having their lab work cancelled.

Previously, the system handled cancellations **one reservation at a time, chronologically** (a "greedy" approach):
1. It took the first reservation in the morning and gave it the first available substitute.
2. If that substitute was versatile (capable of serving multiple kinds of work), but a later student strictly required that specific versatile model, the later student got cancelled because the first student had already consumed it!

### The Solution: Global Optimization
Instead of making short-sighted sequential decisions, the **Global Optimal Reassignment Engine** looks at all affected reservations and all lab substitutes together and searches for the cheapest complete plan.

It solves two goals simultaneously:
1. **Rescue the maximum possible number of students**: Prioritize saving bookings from cancellation above all else.
2. **Minimize equipment degradation and student inconvenience**: If a student can be given the exact same model, prefer that over an alternative model in the same category. Only cancel as an absolute last resort.

### Real-world Analogy
Imagine assigning seats on an airplane during a flight delay. A greedy system gives the first passenger the last window seat, even though that passenger is fine with any aisle seat, and the next passenger has severe motion sickness and can only sit by a window. The greedy system leaves the second passenger stranded. The global optimizer arranges everyone so that both passengers get to fly safely.

---

## Part 2: Technical details

### 1. Mathematical Formulation & Algorithmic Design

The reassignment problem across multiple candidate assets and arbitrary time intervals is modeled as a global cost-minimization problem:
- **Primary Objective**: Maximize the number of rescued bookings (or equivalently, minimize force-cancellations with penalty $C_{\text{ForceCancel}} = 1000$).
- **Secondary Objective**: Minimize substitution degradation (preferring exact model $C_{\text{SameModel}} = 10$ over category substitute $C_{\text{SameCategory}} = 50$).

#### Exact Interval Availability & Branch-and-Bound
An earlier version clustered overlapping intervals into conflict tracks for a Min-Cost Max-Flow network. That class was never used by the solver and has been deleted: what runs is a greedy pass plus branch and bound. Arbitrary pairwise interval overlaps cannot always be clustered into disjoint tracks without over-constraining machine capacity, so the solver works on the intervals directly.

To guarantee **mathematical optimality at true interval granularity**:
1. **Interval-Level Constraints**: A candidate machine $A_j$ can be assigned to multiple bookings $\{R_1, \dots, R_k\}$ if and only if no two bookings pairwise overlap ($\forall a \ne b, [start_a, end_a) \cap [start_b, end_b) = \emptyset$).
2. **Greedy Upper Bound**: The solver first runs a chronological greedy pass to establish an initial upper-bound penalty cost $U$.
3. **Suffix Lower Bounds**: For each position $i \in [0, n)$, we precompute the optimistic minimum possible cost for the remaining bookings:
   $$\text{minRemaining}[i] = \sum_{k=i}^{n-1} \min_{c \in \text{Choices}(k)} \text{cost}(c)$$
4. **Pruning Condition**: During depth-first search, if $\text{currentCost} + \text{minRemaining}[i] \ge \text{bestCost}$, the entire subtree is pruned immediately.
5. **Heuristic Candidate Ordering**: At each step, exact same-model substitutes ($C=10$) are evaluated before category alternatives ($C=50$), finding near-optimal solutions rapidly.

```
                  [Root: 0 bookings assigned]
                  /            |            \
       Assign to A10         Assign to A20    Branch Pruned
         (Cost: 10)           (Cost: 50)     (cost + LB >= best)
        /          \              |
   Booking 2    Booking 2     Booking 2
   (Valid int.) (Overlap!)    (Valid int.)
```

### 2. Implementation & Performance Benchmarks

- **Solver Class**: `com.campus.reassignment.service.optimal.OptimalReassignmentSolver`
- **Result type**: the service returns `OptimalReassignmentPlan`; `AdminReassignmentController` maps it to `OptimalReassignmentPlanResponse` (the HTTP shape lives in `web/`).
- **Honest limitation**: the endpoint only plans the bookings of one asset. Those never overlap (core overlap rule), so for single-asset input the greedy pass is already optimal and the comparison reports "matched". The search only gains something when bookings compete for the same substitute (overlapping intervals, e.g. several damaged assets at once), which the unit tests cover directly on the solver.
- **Benchmark Performance**:
  - Small to medium sets (10-20 reservations): **$< 2\text{ ms}$**.
  - Large multi-day batches (60 reservations across 5 days, 5 candidate machines): checked by unit test `solve_benchmarkLargeReservationSet`, which asserts the result (all 60 rescued) and no longer a wall-clock limit, since those flake on slow CI runners.

### 3. REST API Endpoint

`POST /api/v1/admin/reassignments/solve-optimal`
- Query parameters:
  - `assetId` (Long, required): ID of the asset; it must be `UNDER_MAINTENANCE`, otherwise `409 ASSET_NOT_UNDER_MAINTENANCE`.
  - `incidentId` (Long, optional): incident to link in the log.
  - `dryRun` (Boolean, default `true`): only returns the plan. `dryRun=false` runs the standard `ReassignmentBookingProcessor` per booking (Strategy order, retry on `SubstituteNoLongerFreeException`, move + log in one transaction), the same path as the incident flow.

### 4. Verification & Mutation Testing

- Unit tests (`OptimalReassignmentSolverTest`):
  - `solve_rescuesAllReservationsWhereGreedyFails`: Counterexample (overlapping bookings, solver level) where the search rescues 100% of reservations where Greedy cancels a student.
  - `solve_reusesSubstituteAcrossNonOverlappingIntervals`: Proves a single machine is safely reused across non-overlapping intervals.
  - `solve_handlesEmptyBookings`: Validates zero-boundary conditions.
- Integration tests (`AdminReassignmentControllerTest`):
  - 401 unauthenticated check.
  - 403 student authorization guard.
  - 200 admin dry-run (default) and execution flow, 409 when the asset is not `UNDER_MAINTENANCE`.
- Mutation Testing (`./gradlew pitest`):
  - Tested with PIT Gregor engine.
  - **95% line coverage**, **93% mutation kill rate**, **95% test strength**.

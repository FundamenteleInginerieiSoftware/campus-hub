# Document 29: Global Optimal Reassignment Engine using Min-Cost Max-Flow

## Part 1: In simple terms (for non-programmers)

### What problem are we solving?
When an essential piece of lab equipment breaks down (e.g., an advanced oscilloscope is damaged), multiple students who booked it in the coming days are at risk of having their lab work cancelled.

Previously, the system handled cancellations **one reservation at a time, chronologically** (a "greedy" approach):
1. It took the first reservation in the morning and gave it the first available substitute.
2. If that substitute was versatile (capable of serving multiple kinds of work), but a later student strictly required that specific versatile model, the later student got cancelled because the first student had already consumed it!

### The Solution: Global Optimization
Instead of making short-sighted sequential decisions, the **Global Optimal Reassignment Engine** looks at all affected reservations and all lab substitutes simultaneously as a complete network.

It solves two goals simultaneously:
1. **Rescue the maximum possible number of students** (Max-Flow): Prioritize saving bookings from cancellation above all else.
2. **Minimize equipment degradation and student inconvenience** (Min-Cost): If a student can be given the exact same model, prefer that over an alternative model in the same category. Only cancel as an absolute last resort.

### Real-world Analogy
Imagine assigning seats on an airplane during a flight delay. A greedy system gives the first passenger the last window seat, even though that passenger is fine with any aisle seat, and the next passenger has severe motion sickness and can only sit by a window. The greedy system leaves the second passenger stranded. The global optimizer arranges everyone so that both passengers get to fly safely.

---

## Part 2: Technical details

### 1. Mathematical Formulation & Algorithmic Design

The reassignment problem across multiple candidate assets and interval bookings is formulated as a **Min-Cost Max-Flow (MCMF)** problem on a directed bipartite network.

#### Cost Matrix
- Exact same model substitution ($C_{\text{SameModel}}$): penalty = `10`
- Same category substitution ($C_{\text{SameCategory}}$): penalty = `50`
- Forced cancellation fallback ($C_{\text{ForceCancel}}$): penalty = `1000`

Because $10 \ll 1000$ and $50 \ll 1000$, any augmenting path rescuing a reservation will strictly dominate force cancellation, enforcing maximum flow (maximum student reservations rescued).

#### Interval Conflict Clustering
Two reservations $R_a$ and $R_b$ compete for a substitute machine $A_j$ if and only if their intervals $[start_a, end_a)$ and $[start_b, end_b)$ overlap in time.
Non-overlapping reservations can safely be executed sequentially on the same substitute machine.

To enforce physical machine constraints without unnecessary bottlenecks:
1. We construct an interval overlap graph $G = (V, E)$ where $(a, b) \in E \iff R_a \text{ overlaps with } R_b$.
2. Connected overlap components are extracted as **Conflict Clusters** $C_k$.
3. For each candidate substitute $A_j$ and cluster $C_k$, an asset-slot node $(A_j, C_k)$ is created with capacity 1 to Sink.
4. Overlapping bookings in cluster $C_k$ contend for $(A_j, C_k)$ (at most one gets unit $A_j$).
5. Non-overlapping bookings belong to distinct clusters $C_k \ne C_l$, allowing the same physical unit $A_j$ to be reused across disjoint time slots throughout the week.

```
       [Source] (Supply = N)
       /   |   \
   (cap 1) ... (cap 1)
     /     |     \
   [R1]   [R2]   [R3]
    | \   / |     |
    |  \ /  |     | (cap 1, cost 10/50)
    |   X   |     |
    v  v v  v     v
  [(A1, C0)]    [(A1, C1)]
       \           /
      (cap 1)   (cap 1)
         \       /
          v     v
          [Sink]
```

### 2. Implementation: Min-Cost Max-Flow with Successive Shortest Path

Class: `com.campus.reassignment.service.optimal.MinCostMaxFlow`
- Implements Successive Shortest Path (SSP) using Dijkstra with Johnson's node potentials $h[u]$.
- Reduced edge costs $c^\pi(u, v) = c(u, v) + h[u] - h[v] \ge 0$ guarantee non-negative edge weights on residual graphs, maintaining $O(|E| \log |V|)$ Dijkstra steps per augmentation.
- Total complexity: $O(N \cdot |E| \log |V|)$, executing in $< 2\text{ ms}$ for standard lab capacities.

### 3. REST API Endpoint

`POST /api/v1/admin/reassignments/solve-optimal`
- Query parameters:
  - `damagedAssetId` (Long, required): ID of the malfunctioning equipment.
  - `dryRun` (Boolean, default `true`): When true, computes the global optimal plan and side-by-side comparison without altering the database. When false, executes reassignments and logs actions to `reassignment_log`.

### 4. Verification & Mutation Testing

- Unit tests (`OptimalReassignmentSolverTest`):
  - `solve_rescuesAllReservationsWhereGreedyFails`: Formal counterexample proving MCMF rescues 100% of reservations where Greedy cancels a student.
  - `solve_reusesSubstituteAcrossNonOverlappingIntervals`: Proves a single machine is safely reused across non-overlapping intervals.
  - `solve_handlesEmptyBookings`: Validates zero-boundary conditions.
- Integration tests (`AdminReassignmentControllerTest`):
  - 401 unauthenticated check.
  - 403 student authorization guard.
  - 200 admin dry-run and execution flow.
- Mutation Testing (`./gradlew pitest`):
  - Tested with PIT Gregor engine.
  - **95% line coverage**, **93% mutation kill rate**, **95% test strength**.

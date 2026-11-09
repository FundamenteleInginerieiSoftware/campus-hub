# Document 30: Chain-Shifting Conflict Resolver using Augmenting Paths

## Part 1: In simple terms (for non-programmers)

### What problem are we solving?
Suppose Student C arrives at the lab and urgently needs Equipment X (for example, Oscilloscope #1).
Currently, the system says: "Sorry, Oscilloscope #1 is booked by Student A from 10:00 to 12:00. Request denied."

However, looking at the entire lab:
- Student A is using Oscilloscope #1, but could just as easily use Oscilloscope #2 (identical model).
- Oscilloscope #2 is booked by Student B from 10:00 to 12:00.
- But Oscilloscope #3 (same model) is completely free and idle!

### The Solution: Augmenting Path Chain Shift
Instead of rejecting Student C, the system performs a domino-like **chain shift**:
1. It shifts Student B from Oscilloscope #2 to Oscilloscope #3 (which was idle).
2. Now Oscilloscope #2 is free, so it shifts Student A from Oscilloscope #1 to Oscilloscope #2.
3. Now Oscilloscope #1 is completely free for Student C!

**Result:** All three students get to work on the exact equipment model they need. Nobody was cancelled or compromised, and idle capacity was utilized.

---

## Part 2: Technical details

### 1. Graph Matching & Augmenting Paths

In graph theory and bipartite matching, finding a sequence of shifts that resolves a conflict and accommodates a new entity is equivalent to discovering an **Augmenting Path**:

$$\text{TargetAsset } A_0 \xrightarrow{\text{Res } R_0} \text{Asset } A_1 \xrightarrow{\text{Res } R_1} \dots \xrightarrow{\text{Res } R_k} \text{Idle Asset } A_{k+1}$$

#### Search Algorithm: Breadth-First Search (BFS)
Class: `com.campus.reassignment.service.chain.ChainShiftingConflictResolver`
- Shortest path BFS guarantees finding the **minimum number of relocations**, preventing unnecessary student disruption.
- `visitedAssets` prevents cyclic shifts (e.g., $A \to B \to A$).
- `MAX_CHAIN_DEPTH = 3` bounds computational complexity, ensuring execution completes in under 5 ms.

#### Execution Order (Reverse Topological Order)
When applying an augmenting path:
1. Shift terminal booking $R_k \to A_{k+1}$ (which is idle).
2. Now $A_k$ is idle; shift $R_{k-1} \to A_k$.
3. Continue in reverse until $R_0 \to A_1$.
4. $A_0$ is now completely free with zero transient database collision.

### 2. REST API Endpoint

`POST /api/v1/admin/reassignments/chain-shift`
- Query parameters:
  - `targetAssetId` (Long, required): The equipment item to free.
  - `startTime` (ISO-8601, required): Start of the needed time slot.
  - `endTime` (ISO-8601, required): End of the needed time slot.
  - `dryRun` (Boolean, default `true`): If true, returns the plan and explanation without modifying the database. If false, atomically executes all relocations and logs to `reassignment_log`.

### 3. Verification & Architecture

- Unit tests (`ChainShiftingConflictResolverTest`):
  - `planChainShift_targetAlreadyFree`: Verifies immediate exit when no blockers exist.
  - `planChainShift_singleStepAugmentingPath`: Tests direct 1-step reallocation.
  - `planChainShift_twoStepAugmentingPathChain`: Tests 2-step chain and reverse execution order.
  - `planChainShift_noAugmentingPathFailsGracefully`: Tests clean failure when all substitutes are occupied.
- Controller integration tests (`AdminReassignmentControllerTest`):
  - 401 unauthenticated guard.
  - 403 student authorization guard.
  - 200 admin plan response.
- Architecture rules:
  - Resides strictly in `reassignment/`, accessing `reservationApi` and `catalogApi`.
  - Zero cyclic dependencies, ArchUnit compliant.

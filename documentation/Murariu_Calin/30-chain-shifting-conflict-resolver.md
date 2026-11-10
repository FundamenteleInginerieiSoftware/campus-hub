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

**Result:** Nobody is cancelled. Each moved student gets a unit of the same model when one is free; if not, the search may use a unit of the same category, which can be a **different model**. The endpoint only frees X; it does not book it for Student C.

If X is in use right now (checked out or overdue), moving its future bookings would not free it, so the system answers "not resolved" and moves nobody.

---

## Part 2: Technical details

### 1. Search (`ChainShiftingConflictResolver.planChainShift`)

| Step | What the code does |
|---|---|
| 1 | Target already free (`isAssetFree`) → resolved, 0 steps |
| 2 | Blockers = CONFIRMED bookings on the target that overlap the window (`findUpcomingActiveByAsset`) |
| 3 | Target has a CHECKED_OUT booking overlapping the window, or is OVERDUE (`findCurrentCheckout`) → not resolved |
| 4 | For each blocker: BFS over candidate units (same model first, then same category). A free candidate ends the chain. A candidate blocked by exactly one CONFIRMED booking and no checkout becomes the next link |
| 5 | Depth limit `MAX_CHAIN_DEPTH = 3`; each path keeps its own visited set (no A → B → A) |
| 6 | Units used by one chain are not offered to the next blocker on the same target (conservative, never two steps on one unit) |

BFS finds the chain with the fewest moves. Steps are returned in reverse order: the last booking moves to the idle unit first, so every move lands on a unit that is already free.

### 2. Execution (`ChainShiftExecutor.executeChainShift`)

- One `@Transactional` method for the whole chain. If `moveToAsset` returns false or throws `SubstituteNoLongerFreeException`, it throws `409 CHAIN_SHIFT_EXECUTION_FAILED` and everything rolls back (moves and `reassignment_log` rows).
- Emails are sent in `afterCommit`, so a rolled-back chain sends none.

### 3. REST API

`POST /api/v1/admin/reassignments/chain-shift?targetAssetId=&startTime=&endTime=&dryRun=`
- Times are local (`2026-11-15T10:00:00`); a `Z` or offset is rejected with 400, like the reservation endpoints (#233). `startTime` must be before `endTime` (400 `VALIDATION_FAILED`).
- `dryRun` defaults to `true` (plan only). `dryRun=false` applies the plan.

### 4. Tests

| Test | Checks |
|---|---|
| `ChainShiftingConflictResolverTest` | already free, 1-step, 2-step + reverse order, no path, target only in use, target checked out + CONFIRMED blocker, candidate OVERDUE skipped |
| `ChainShiftRollbackIT` (PostgreSQL) | 2-step chain, step 2 fails → step 1 undone, no log rows, no emails |
| `ChainShiftExecutorTest` | step order, failure → 409 (Mockito, no transaction) |
| `AdminReassignmentControllerTest` | 401, 403, 200, default `dryRun=true`, 400 for start ≥ end and for `Z` |

# Photo Cleanup When the Commit Outcome Is Unknown (Vișan Ștefania)

**Role:** Member 4 (incidents)
**Date:** 2026-11-27
**Files:** `backend/src/main/java/com/campus/incident/service/IncidentService.java`, `backend/src/test/java/com/campus/incident/service/IncidentServiceTest.java`, `docs/whats-left.md`, `AGENTS.md`, `documentation/Visan_Stefania/README.md`
**PR:** #368 (Refs #269, point 4 of Matei's read-through)

---

## Part 1: In simple terms

**The problem:** when a student adds or replaces the photo of an incident, the file is saved first and the database row is updated after. At the end of the transaction there is a cleanup step: if the save went through, the old photo is deleted; if it was rolled back, the new file is deleted (no row points to it). Spring has a third outcome too: **unknown**, when the connection drops in the middle of the commit and nobody knows if the row was saved. The old code treated "unknown" like a rollback and deleted the new photo. If the commit had in fact gone through, the incident pointed to a photo that no longer existed.

**What I did:** on "unknown", nothing is deleted and a warning goes to the log. A spare file only wastes a little space; a missing one breaks the incident page.

---

## Part 2: Technical details

### The change

```java
public void afterCompletion(int status) {
    switch (status) {
        case STATUS_COMMITTED -> deleteQuietly(oldKey);   // the replaced photo
        case STATUS_ROLLED_BACK -> deleteQuietly(newKey); // the file no row points to
        default -> log.warn("... keeping both files (old {}, new {})", oldKey, newKey); // STATUS_UNKNOWN
    }
}
```

Before: `deleteQuietly(status == STATUS_COMMITTED ? oldKey : newKey)`.

### Why keep both instead of guessing

| Option | Problem |
|---|---|
| Treat unknown as rollback (old code) | If the commit happened, the incident points to a deleted photo: 404 on the photo, a broken page |
| Treat unknown as commit | If it rolled back, the row still points to the old photo, which would be deleted: same break, other side |
| Keep both + warning (chosen) | Always correct for the row; the cost is at most one orphan file, visible in the log |

### Test

`IncidentServiceTest.Photo.insideATransaction_theCleanupFollowsTheOutcome` (parameterized): it starts a real `TransactionSynchronization`, uploads a photo over an existing one, checks that nothing is deleted before the transaction ends, then runs `afterCompletion` with each outcome:

| Outcome | Deleted |
|---|---|
| `STATUS_COMMITTED` (0) | old photo only |
| `STATUS_ROLLED_BACK` (1) | new file only |
| `STATUS_UNKNOWN` (2) | nothing |

**Break-it check:** putting the old one-liner back fails exactly the `STATUS_UNKNOWN` case.

### Checks

`./gradlew build` passes (Spotless, unit tests, ITs, ArchUnit).

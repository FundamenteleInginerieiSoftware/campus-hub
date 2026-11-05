# Document 10: Multi-Thread Concurrency & Pessimistic Row Locking

**Author:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Module:** `com.campus.reservation`  
**Test Suite:** `com.campus.reservation.service.ReservationConcurrencyIT`  

---

## Part 1: In Simple Terms

In a university lab booking system, popular equipment (such as high-end oscilloscopes or GPU servers) often experiences "rush hour" booking collisions: two students simultaneously open the booking page and click "Book" for the exact same lab hour down to the exact millisecond.

If a naive system is used:
1. Student A's request asks the database: *"Is Oscilloscope #1 free from 14:00 to 16:00?"* -> Database replies: *"Yes"*.
2. Concurrently, Student B's request asks: *"Is Oscilloscope #1 free from 14:00 to 16:00?"* -> Database replies: *"Yes"*.
3. Both requests proceed to save the reservation, resulting in a disastrous **double booking**: two students arrive at the lab expecting the same machine.

To prevent this race condition, our architecture uses **pessimistic row locking** (`SELECT ... FOR UPDATE`):
- Before checking whether an equipment is free, Student A's transaction explicitly locks the physical row of that equipment in the PostgreSQL `assets` table.
- When Student B's transaction tries to book the same machine at the same instant, PostgreSQL puts Student B on hold (sleep) at the row lock gate.
- Student A confirms the slot is free, inserts their reservation, and commits the transaction (releasing the lock).
- Student B's transaction now wakes up, acquires the lock, performs the overlap check, and discovers Student A's newly committed reservation.
- Student B is rejected cleanly with a friendly `409 RESERVATION_CONFLICT`.

This document records the end-to-end integration test (`ReservationConcurrencyIT`) that proves this behavior under genuine multi-threaded execution.

---

## Part 2: Technical Details

### 1. The Locking Chain

The lock order is enforced in `ReservationService.createReservation`:
```
Student Thread 1                           Student Thread 2
      │                                          │
      ├── 1. CatalogApi.lockForBooking(assetId)  ├── 1. CatalogApi.lockForBooking(assetId)
      │      (SELECT ... FOR UPDATE on asset)    │      [ BLOCKS at DB row lock ]
      │                                          │             │
      ├── 2. checkOverlap(assetId, start, end)   │             │
      │      (0 overlaps found)                  │             │
      │                                          │             │
      ├── 3. reservationRepository.save(...)     │             │
      │                                          │             │
      └── 4. COMMIT & RELEASE LOCK               │             │
                                                 │      [ UNBLOCKS & ACQUIRES LOCK ]
                                                 │             │
                                                 ├── 2. checkOverlap(assetId, start, end)
                                                 │      (Finds Thread 1's booking!)
                                                 │             │
                                                 └── 3. THROW ConflictException(
                                                            "RESERVATION_CONFLICT")
```

- **Method:** `CatalogApi.lockForBooking(Long assetId)`
- **Query:** `AssetRepository.findByIdForUpdate(Long assetId)` annotated with `@Lock(LockModeType.PESSIMISTIC_WRITE)`.
- **Transaction boundary:** `@Transactional` on `createReservation`. The row lock is held until the entire booking transaction commits.

### 2. Multi-Threaded Test Design (`ReservationConcurrencyIT`)

1. **Thread Pool:** `Executors.newFixedThreadPool(2)`
2. **Synchronization Barriers:**
   - `readyLatch (CountDownLatch(2))`: ensures both worker threads have initialized independent Spring Security contexts (`JwtAuthenticationToken` for Student 101 and Student 102).
   - `startLatch (CountDownLatch(1))`: fires simultaneously, releasing both threads into `reservationService.createReservation()` at the exact same instant.
3. **Assertions:**
   - Exactly one thread finishes successfully (`successCount == 1`).
   - Exactly one thread catches `ConflictException` with code `"RESERVATION_CONFLICT"` (`conflictCount == 1`).
   - `reservationRepository.findAll()` confirms exactly 1 reservation exists in PostgreSQL.

### 3. Per-User Advisory Lock for Active Quota Enforcement (`#169`)

While `CatalogApi.lockForBooking(assetId)` serializes concurrent bookings on the **same** asset, a single student who already has 1 active booking could previously fire two simultaneous requests for two **different** assets (`assetId = 602` and `assetId = 603`). Because those requests lock different rows in `assets`, both could read `countByUserIdAndStatusIn(userId, ACTIVE_STATUSES) == 1` under PostgreSQL `READ COMMITTED` and end up with 3 active bookings.

- **Fix (`ReservationRepository.lockUserQuota`):**
  ```java
  @Query(value = "SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:userId)) AS quota_lock", nativeQuery = true)
  long lockUserQuota(@Param("userId") Long userId);
  ```
- **Acyclic Lock Ordering (No Deadlocks):**
  Every booking transaction acquires locks in the strict order `asset row lock -> user advisory lock`. No transaction ever acquires an asset row lock while holding a user advisory lock, making the wait-for graph a DAG (cycle-free).
- **Multi-Threaded Verification (`sameStudentTwoDifferentAssetsCannotBypassActiveQuotaLimit`):**
  `ReservationConcurrencyIT` verifies that when Student 101 (with 1 active booking on asset `601`) simultaneously books assets `602` and `603` across two threads, `successCount == 1`, `limitReachedCount == 1` (`409 RESERVATION_LIMIT_REACHED`), and the database contains exactly 2 active bookings.

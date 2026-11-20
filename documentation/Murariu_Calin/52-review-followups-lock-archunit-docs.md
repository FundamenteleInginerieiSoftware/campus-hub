# 52 — Review Follow-ups & PIT Mutation Hardening: cancelReservation Row Lock, Deterministic Transfer Race IT, ArchUnit Rule 15 & Docs (#329, #333)

## Part 1: In simple terms

This PR bundles the four non-blocking cleanup and hardening items noted by Matei in his approved reviews on #329 and #333, plus PIT mutation test hardening across `reservation`, `reassignment`, and `common.time`:
1. **Row lock on student cancel:** `cancelReservation` now locks the reservation row (`FOR UPDATE`), so a classmate rejecting a transfer at the exact same moment waits cleanly instead of causing a `STALE_VERSION` error.
2. **Deterministic transfer-vs-disable tests:** In `ReservationConcurrencyIT`, both `acceptTransfer` and `rejectTransfer` are now tested against `cancelUpcomingReservationsForUser` using PostgreSQL `pg_stat_activity` polling so the interleaving is 100% deterministic (never timing-dependent).
3. **ArchUnit guard against `default` methods in `..api..`:** Added Rule 15 in `CodingConventionsArchitectureTest` forbidding `default` methods on cross-module `api` interfaces so nobody can accidentally bypass Spring `@Transactional` / `@Async` proxies via self-invocation again.
4. **Docs aligned with `sourceAssetId`:** Updated the Strategy pattern doc, reassignment sequence diagram, final report chapter, and doc 47 so every reference matches the 4-arg `moveToAsset` / 3-arg `forceCancel` signatures.
5. **PIT mutation testing pass (`./gradlew pitest`):** Verified 100% mutation kill rate on `reassignment` (73/73) and `common.time`, and killed all 4 real-rule survivors introduced by recent PRs in `reservation` (615/620 killed, 99.2% across `reservation` + `reassignment`, with only 5 equivalent/logging-guard mutants remaining).

---

## Part 2: Technical details

| File | Change |
|---|---|
| `ReservationService.java` | Switched `cancelReservation` to `ReservationServiceSupport.loadOwnedOrAdminForUpdate(...)`; updated `ReservationServiceTest` stubs to `findByIdForUpdate`. |
| `ReservationConcurrencyIT.java` | Replaced the timing-based accept test with deterministic `assertTransferCallWaitsBehindCancelUpcomingAndGetsInvalidTransition(...)` using `pg_stat_activity` lock polling, covering both `acceptTransfer` and `rejectTransfer`. |
| `CodingConventionsArchitectureTest.java` | Added Rule 15 (`api_interfaces_must_not_declare_default_methods`) checking all non-static, non-abstract, non-synthetic methods on interfaces under `com.campus..api..`. |
| `TransferServiceTest.java` | Added `inOrder(reservationTransferRepository, reservationRepository)` assertions verifying `findReservationIdById` → `reservationRepository.findByIdForUpdate` → `reservationTransferRepository.findByIdForUpdate` lock order in `acceptTransfer`, `rejectTransfer`, and `cancelTransfer` (kills 3 `VoidMethodCallMutator` survivors). |
| `UserDisabledReservationListener.java` | Switched `requiresNewTx.execute(...)` to `requiresNewTx.executeWithoutResult(...)` so the lambda has no unused boxed return value (eliminates the `EmptyObjectReturnValsMutator` survivor). |
| Docs | Updated `docs/patterns/strategy-pattern.md`, `docs/diagrams/reassignment-sequence.mmd`, `docs/report/reservations-and-reassignment.md`, and `documentation/Murariu_Calin/47-reassignment-source-asset-guard.md`. |

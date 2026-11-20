# 53 — Review Follow-ups (#336, #338): cancelBundle & markNoShow Row Locks, Deterministic cancelReservation Race IT & Personal Docs Sync

## Part 1: In simple terms

This PR addresses the four non-blocking items from Matei's review on #336:
1. **Deterministic `rejectTransfer` vs `cancelReservation` race IT:** Added `cancelReservationWaitsBehindRejectTransferAndSucceedsWithoutStaleVersion` in `ReservationConcurrencyIT` using `pg_stat_activity` lock polling to prove `cancelReservation` waits on `FOR UPDATE` and ends `CANCELLED` without a `STALE_VERSION` error.
2. **Row locks (`FOR UPDATE`) on `cancelBundle` and `markNoShow`:** Added `ReservationRepository.findByBundleIdForUpdate` (`ORDER BY r.id ASC`, matching `findUpcomingConfirmedByUserForUpdate` so multi-row lockers never deadlock) for `cancelBundle`, plus a deterministic `cancelBundleWaitsBehindCancelUpcomingAndCompletesWithoutStaleVersion` race IT and a `@DataJpaTest` ordering check; switched `markNoShow` from `findById` to `findByIdForUpdate`.
3. **Personal docs 02, 11, and 15 synced with `sourceAssetId`:** Updated the 4-arg `moveToAsset` and 3-arg `forceCancel` signatures in `02-reservation-module.md`, `11-strategy-pattern-design.md`, and `15-test-plan-pit-mutation-and-reassignment-architecture.md`.
4. **`.as()` messages & `AGENTS.md` §7 placement:** Added `.as(...)` descriptions to the follow-up assertions in `TransferServiceTest` and moved the `cancelReservation` / transfer lock note from the ArchUnit bullet to the Reservations bullet in `AGENTS.md` §7.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `ReservationRepository.java` | Added `@Lock(PESSIMISTIC_WRITE) findByBundleIdForUpdate(UUID bundleId)` ordered by `r.id ASC`. |
| `ReservationService.java` | Updated `loadOwnedOrAdminBundle(UUID, boolean forUpdate)` (`true` in `cancelBundle`, `false` in `getBundle`) and switched `markNoShow` to `findByIdForUpdate`. |
| `ReservationConcurrencyIT.java` | Added `cancelReservationWaitsBehindRejectTransferAndSucceedsWithoutStaleVersion` and `cancelBundleWaitsBehindCancelUpcomingAndCompletesWithoutStaleVersion` using `pg_stat_activity` lock polling. |
| `ReservationRepositoryTest.java` | Added `findByBundleIdForUpdate_returnsOrderedByIdAsc`. |
| `ReservationServiceTest.java`, `ReservationServiceRulesTest.java`, `TransferServiceTest.java` | Updated `cancelBundle` / `markNoShow` stubs to `findByBundleIdForUpdate` / `findByIdForUpdate` and added `.as(...)` messages on follow-up assertions in `TransferServiceTest`. |
| `02-reservation-module.md`, `11-strategy-pattern-design.md`, `15-test-plan-pit-mutation-and-reassignment-architecture.md` | Updated `moveToAsset` and `forceCancel` call shapes to include `expectedSourceAssetId` / `sourceAssetId`. |

# 51 — Remove Self-Invoking Default Overloads on ReservationApi (#327 Follow-up)

## Part 1: In simple terms

In PR #327 we added a source-asset guard (`expectedSourceAssetId`) to `ReservationApi.moveToAsset` and `forceCancel`, and kept the old 3-argument and 2-argument methods as Java interface `default` methods delegating to the new ones.

During post-merge review, Matei caught a subtle Spring proxy bug: calling a `default` method on an interface invokes the 4-arg/3-arg implementation through `this` (self-invocation), bypassing Spring's `@Transactional(noRollbackFor = ...)` proxy! Because no production code calls the old overloads anymore, we deleted both `default` methods so every caller explicitly goes through the proxied, guarded signatures, and added a two-thread `CyclicBarrier` race test in `AssetRetiredReassignmentIT`.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `ReservationApi.java` | Removed `default moveToAsset(id, newAssetId, reason)` and `default forceCancel(id, reason)` so Spring AOP `@Transactional` proxies can never be bypassed via interface-default self-invocation. |
| `ReservationServiceTest.java`, `ReservationServiceRulesTest.java` | Updated the remaining unit test calls to pass `null` explicitly as `expectedSourceAssetId`. |
| `AssetRetiredReassignmentIT.java` | Added `twoConcurrentHandlersBehindBarrierMoveBookingOnlyOnce()` racing two `reassignmentService.handle(AssetRetiredEvent)` calls behind a `CyclicBarrier(2)` against PostgreSQL, verifying a single `reassignment_log` row. |
| `docs/modules/reservation.md`, `docs/modules/reassignment.md` | Synced `ReservationApi` signatures and the reassignment sequence diagram with `sourceAssetId`. |

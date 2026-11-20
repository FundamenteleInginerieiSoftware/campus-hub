# Documentation: Murariu Călin

**Member 3:** Reservations & Auto-Reassignment Engine  
**GitHub:** [@Swiorx](https://github.com/Swiorx)  

---

## Overview

This folder contains the personal engineering documentation and progress logs for the **Campus Equipment & Incident Hub** project (FIS course).

Each document is structured in two parts:
1. **Part 1: In simple terms** — High-level conceptual explanation suitable for non-programmers, product managers, and introductory defense questions.
2. **Part 2: Technical details** — Deep architectural breakdown, database schemas, function-by-function explanations, design patterns, transaction boundaries, and test matrices.

---

## Documents Index

| # | Document | Topic | Key Concepts |
|---|---|---|---|
| 01 | [`01-common-time-clock.md`](./01-common-time-clock.md) | Shared Clock & Time Zone | `ClockConfig`, `Europe/Bucharest`, deterministic testing, avoiding `now()` |
| 02 | [`02-reservation-module.md`](./02-reservation-module.md) | Reservation Module | Pessimistic locking, 10-case overlap matrix, lifecycle state machine, IDOR protection, REST API |
| 03 | [`03-reassignment-engine.md`](./03-reassignment-engine.md) | Auto-Reassignment Engine | Strategy pattern (`SameModelStrategy`, `SameCategoryStrategy`), Observer pattern (`@TransactionalEventListener`), `reassignment_log`, `REQUIRES_NEW` |
| 04 | [`04-reservation-lifecycle-and-jobs.md`](./04-reservation-lifecycle-and-jobs.md) | Autonomous Lifecycle Jobs | Scheduled no-show expiration, overdue detection, preventive cascade move to free substitutes |
| 05 | [`05-archunit-architecture-rules.md`](./05-archunit-architecture-rules.md) | ArchUnit Architecture Rules | Package boundaries, module encapsulation, cycle detection, entity encapsulation, coding conventions |
| 06 | [`06-asset-availability-endpoint.md`](./06-asset-availability-endpoint.md) | Equipment Availability Query | Temporal availability check, category bulk availability, `findBusyAssetIds`, separation of catalog vs reservation |
| 07 | [`07-admin-desk-reservations.md`](./07-admin-desk-reservations.md) | Admin Desk Operations | Check-out window (`-15m` to `+30m`), item return (`CHECKED_OUT`/`OVERDUE`), JPA `Specification` search |
| 08 | [`08-frontend-reservations-and-booking.md`](./08-frontend-reservations-and-booking.md) | Frontend Reservations & Booking Calendar | My Reservations page, Google Calendar week view, 20:00 closing rule, integration guide for Liviu |
| 09 | [`09-admin-reassignment-logs.md`](./09-admin-reassignment-logs.md) | Admin Reassignment Logs Endpoint | `GET /api/v1/admin/reassignments`, audit trail pagination, `ReassignmentLogResponse`, unblocking frontend logs |
| 10 | [`10-concurrency-pessimistic-locking.md`](./10-concurrency-pessimistic-locking.md) | Multi-Thread Concurrency & Row Locking | Pessimistic `SELECT FOR UPDATE`, `ReservationConcurrencyIT`, preventing double booking |
| 11 | [`11-strategy-pattern-design.md`](./11-strategy-pattern-design.md) | Strategy Pattern Design & Architecture | GoF Strategy pattern deep dive, Spring `@Order` injection, OCP extensibility, tie-breaking, defense Q&A |
| 12 | [`12-quality-assurance-and-ci-architecture-tests.md`](./12-quality-assurance-and-ci-architecture-tests.md) | Quality Assurance & CI Architecture Rules | Automated ArchUnit quality gates, shared Clock enforcement, transactional boundaries, seeder/listener test expansion |
| 13 | [`13-frontend-caching-and-cancel-guards.md`](./13-frontend-caching-and-cancel-guards.md) | Frontend Asset Cache & Cancel Guards | `fetchedAssetIdsRef` deduplication, pre-start cancellation guard, stale booking error reset |
| 14 | [`14-swagger-examples-and-mutation-test-gaps.md`](./14-swagger-examples-and-mutation-test-gaps.md) | Swagger Examples & Mutation Test Gaps | Slot-aligned Swagger examples, `ReassignmentRollbackIT` DB isolation, PIT survivor elimination in reservation/reassignment |
| 15 | [`15-test-plan-pit-mutation-and-reassignment-architecture.md`](./15-test-plan-pit-mutation-and-reassignment-architecture.md) | Test Plan, PIT Mutation Testing & Reassignment Architecture | Formalized SQA test plan, testing pyramid, 100% PIT mutation coverage, `REQUIRES_NEW` reassignment engine |

| 16 | [`16-simultaneous-multi-asset-bundle-reservations.md`](./16-simultaneous-multi-asset-bundle-reservations.md) | Simultaneous Multi-Asset Bundle Reservations | Atomic kit booking (2..5 assets), deadlock prevention via sorted locks, collective cancellation |

| 17 | [`17-active-reservation-extension.md`](./17-active-reservation-extension.md) | Active Reservation Duration Extension | Atomic extension, pessimistic locking, slot alignment, overlap check, zero corruption |
| 18 | [`18-p2p-reservation-transfer.md`](./18-p2p-reservation-transfer.md) | Peer-to-Peer Reservation Transfer Workflow | Two-way handshake, advisory quota locking, state machine, zero double-booking |
| 19 | [`19-reservation-hold-slot-locking.md`](./19-reservation-hold-slot-locking.md) | Reservation Hold & Automated Expiry | 1-minute temporary slot hold, concurrency protection, anti-hoarding hold quota, automated expiration sweep |
| 20 | [`20-my-reservations-management-and-incident-shortcut.md`](./20-my-reservations-management-and-incident-shortcut.md) | My Reservations Management, P2P Transfer & Identity | Self-service extension modal, P2P transfer with student identity card, bundle badge, incident reporting shortcut |
| 21 | [`21-reservation-slot-hold-countdown-and-release.md`](./21-reservation-slot-hold-countdown-and-release.md) | Booking Calendar Slot Hold & Countdown | Interactive 1-min slot hold, live countdown timer bar, manual release, unmount leak safety |
| 22 | [`22-catalog-quick-book-and-asset-details-report-button.md`](./22-catalog-quick-book-and-asset-details-report-button.md) | Catalog Quick Book Modal & Asset Details Report | Overlay booking calendar directly on catalog cards, non-retired asset problem report button |
| 23 | [`23-bundle-transfer-hold-security-hardening.md`](./23-bundle-transfer-hold-security-hardening.md) | Bundle, Transfer & Hold Security Hardening | Multi-asset bundle privacy, P2P transfer unique index & role checks, hold overlap verification, cascade transfer cancellation |
| 24 | [`24-reservation-api-book-bundle-facade-and-project-architecture.md`](./24-reservation-api-book-bundle-facade-and-project-architecture.md) | ReservationApi bookBundle Facade & Project Assistant | In-process atomic booking facade (1..5 assets) for project assistant, ArchUnit leaf module rule for project |
| 25 | [`25-bundle-booking-confirmation-banner.md`](./25-bundle-booking-confirmation-banner.md) | Bundle Booking Confirmation Banner in My Reservations | Navigation state handling for `bookedBundleId`, item count banner formatting, project kit feedback |
| 26 | [`26-reservation-bundle-quota-after-commit-notifications-and-guards.md`](./26-reservation-bundle-quota-after-commit-notifications-and-guards.md) | Reservation Bundle Quota, After-Commit Emails & Edge Guards | Distinct session counting (`coalesce(bundle_id, id)`), `afterCommit` notification sync, anti-probing, hold cooldown, bundle extend guard |
| 27 | [`27-cancel-upcoming-reservations-for-user-facade.md`](./27-cancel-upcoming-reservations-for-user-facade.md) | Cancel Upcoming Reservations for User Facade & Admin Endpoint (#229) | Public facade method `cancelUpcomingReservationsForUser`, bulk cancel endpoint, transfer cascade cancellation, after-commit emails |
| 31 | [`31-reusable-idempotency-request-fingerprinting.md`](./31-reusable-idempotency-request-fingerprinting.md) | Reusable Idempotency & Request Fingerprinting | SHA-256 fingerprinting, atomic persistence, cached replay, concurrent request lock, payload mismatch |
| 33 | [`33-reservation-reassignment-code-review-fixes.md`](./33-reservation-reassignment-code-review-fixes.md) | Code Review #267 Fixes | Concurrency conflict retries, hold race guard & mismatch release, `isAssetFree` holds, bundle hold confirm, status-first check, overdue cascade isolation, row lock on return/overdue |
| 34 | [`34-same-model-substitute-suggestions.md`](./34-same-model-substitute-suggestions.md) | Same-Model Substitute Suggestions on Booking Conflict | `AvailabilityResponse` substitute IDs, same-room-first sorting in `ReservationService`, in-place unit switch in `CalendarBookingView` |
| 35 | [`35-hold-prefilter-calendar-nav-and-test-hardening.md`](./35-hold-prefilter-calendar-nav-and-test-hardening.md) | Hold Prefilter Alignment, Calendar Week Nav Guard & Test Hardening | `isAssetFree` own-hold exclusion, `CalendarBookingView` week-nav loading guard, stale hold tests, 3-item bundle count, ArchUnit Rule 1 strictness |
| 36 | [`36-reservation-frontend-hooks-and-modals-refactor.md`](./36-reservation-frontend-hooks-and-modals-refactor.md) | Reservation Frontend Constants, Slot-Hold Hook & Modal Refactor | Shared `bookingRules` & formatters, `useSlotHold` hook, extracted Cancel/Extend/Transfer modals, `MyReservationsPage` cleanup (Refs #267) |
| 37 | [`37-extract-hold-and-transfer-services.md`](./37-extract-hold-and-transfer-services.md) | Extract HoldService & TransferService (#267 §2) | `HoldService`, `TransferService`, `ReservationSlotValidator`, single `@RequiredArgsConstructor` in `ReservationService` |
| 38 | [`38-reservation-lifecycle-and-code-cleanup.md`](./38-reservation-lifecycle-and-code-cleanup.md) | Reservation Lifecycle & Code Cleanup (#267) | Single 1-minute hold sweep, unified `ReservationRepository` active overlap queries, clean `ReservationHold.confirm()`, `NO_RESERVATION_ID`, `Objects::isNull` |
| 39 | [`39-used-minutes-by-asset-api.md`](./39-used-minutes-by-asset-api.md) | Real Equipment Usage Minutes API (#289) | `ReservationApi.usedMinutesByAsset`, `findCheckedOutOverlapping` JPQL query, `[from, to]` clipping with `Clock` |
| 40 | [`40-calendar-feed-token-and-ics-writer.md`](./40-calendar-feed-token-and-ics-writer.md) | Calendar Feed Token Management & iCalendar Writer | `calendar_feeds` SHA-256 token hash, `CalendarTokenService`, `/me/calendar-feed` endpoints, RFC 5545 `IcsWriter` |
| 41 | [`41-calendar-feed-endpoint.md`](./41-calendar-feed-endpoint.md) | Calendar Feed Endpoint & Service | `findByUserIdOverlappingRange`, `CalendarFeedService` 30-day window + catalog caching, `GET /api/v1/calendar/{token}.ics` |
| 42 | [`42-calendar-sync-page.md`](./42-calendar-sync-page.md) | Calendar Sync Page (Frontend) | `calendarFeed.ts` API wrapper, 3-state `CalendarSyncPage`, `CopyButton`, `.ics` download, replace/revoke confirm modals |
| 43 | [`43-active-user-check-after-quota-lock.md`](./43-active-user-check-after-quota-lock.md) | Active User Re-check After Quota Lock (#229) | `requireActiveUserAfterLock` in `ReservationService`, `HoldService`, `TransferService`; unit + PostgreSQL concurrency test |
| 44 | [`44-asset-retired-reassignment-flow.md`](./44-asset-retired-reassignment-flow.md) | Asset Retirement Reassignment Flow (#245) | Flyway nullable `reassignment_log.incident_id`, `LocalAssetRetiredListener`, `ReassignmentService.handle(AssetRetiredEvent)`, PostgreSQL IT |
| 45 | [`45-calendar-sync-button-in-my-reservations.md`](./45-calendar-sync-button-in-my-reservations.md) | Calendar Sync Button in My Reservations (#314) | Moved Calendar sync from student sidebar into a header button on `MyReservationsPage`, kept `/calendar-sync` route and top-bar title |
| 46 | [`46-reassignment-retire-nits.md`](./46-reassignment-retire-nits.md) | Reassignment Retire Flow Review Nits (#311 Follow-up) | Flyway rename `reassignment_log.trigger` → `triggered_by`, generated IDs in `AssetRetiredReassignmentIT`, ER diagram & module doc sync |
| 47 | [`47-reassignment-source-asset-guard.md`](./47-reassignment-source-asset-guard.md) | Source-Asset Guard on Reassignment Move & Force-Cancel (#319) | `expectedSourceAssetId` + `findByIdForUpdate` in `ReservationApi.moveToAsset`/`forceCancel` to prevent duplicate log/email on concurrent incident + retire |
| 48 | [`48-my-reservations-pickup-pass-button.md`](./48-my-reservations-pickup-pass-button.md) | "Show pickup pass" Button in My Reservations (#322) | Added a `Show pickup pass` shortcut button linking to `/pickup-pass` on today's `CONFIRMED` rows in `MyReservationsPage.tsx` |
| 49 | [`49-disable-concurrency-followups.md`](./49-disable-concurrency-followups.md) | Disable-Cancel Concurrency Follow-ups (#229, #310, #322) | `FOR UPDATE` in `cancelUpcomingReservationsForUser`, `findByIdForUpdate` in `acceptTransfer`, `pg_stat_activity` polling in `ReservationConcurrencyIT` |
| 50 | [`50-user-disabled-event-listener.md`](./50-user-disabled-event-listener.md) | UserDisabledEvent Listener in Reservation Module (#229) | `UserDisabledReservationListener` (`AFTER_COMMIT` + `REQUIRES_NEW` via `TransactionTemplate`, non-`@Async`), unit + PostgreSQL IT, doc 27 contract sync |
| 51 | [`51-remove-reservation-api-default-overloads.md`](./51-remove-reservation-api-default-overloads.md) | Remove Self-Invoking Default Overloads on ReservationApi (#327 Follow-up) | Removed interface `default` delegates on `ReservationApi.moveToAsset`/`forceCancel` that bypassed Spring `@Transactional`, added `CyclicBarrier` race IT, synced module docs |



---

## Responsibilities & Module Ownership

- **`common/time`**: Centralized `Clock` bean injected by all modules.
- **`com.campus.reservation`**:
  - Flyway migration `V2026_10_25_1800__reservation_create_table.sql`
  - Entity `Reservation`, repository with overlap formula `start < :end AND end > :start`
  - Business rules: 08:00–20:00 opening hours, :00 or :30 slot alignment, 30m–4h duration, max 2 active bookings, max 14 days ahead
  - `ReservationService` implementing public `ReservationApi`
  - REST endpoints under `/api/v1/reservations/**`
  - Sample dev seeder `ReservationSeeder`
- **`com.campus.reassignment`**:
  - Flyway migration `V2026_10_26_0135__reassignment_create_log.sql`
  - `ReassignmentLog` entity and repository
  - `SubstituteStrategy` interface, `SameModelStrategy` & `SameCategoryStrategy`
  - Event listener `LocalAssetDamagedListener` on `AssetDamagedEvent`
  - Isolated transaction engine (per-booking `Propagation.REQUIRES_NEW`)

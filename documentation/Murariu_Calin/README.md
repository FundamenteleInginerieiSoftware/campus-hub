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

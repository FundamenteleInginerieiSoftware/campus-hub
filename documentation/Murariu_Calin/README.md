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
  - Isolated transaction engine (`Propagation.REQUIRES_NEW`)

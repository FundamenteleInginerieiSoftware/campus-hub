# 04 — Non-CRUD Scheduling Algorithms, Simulation & Lifecycle Jobs

**Author:** Călin Murariu (`@Swiorx`)  
**Modules:** `com.campus.reservation`, `com.campus.reassignment`  
**PRs:** #58, #59, #60, #61  

---

## Part 1: In simple terms

A university equipment reservation system is much more than a simple form that saves rows to a database:
1. **What if an administrator wants to calibrate or service an oscilloscope next Tuesday?** Instead of guessing how many students will be disrupted, the **Dry-Run Maintenance Impact Simulator** (#58) runs a "what-if" simulation in memory and reports exactly which bookings would be seamlessly moved to another oscilloscope and which ones would have to be cancelled.
2. **What if a student needs an oscilloscope from 10:00 to 12:00, but it is already booked?**
   - The **Smart Slot & Substitute Recommender** (#61) scans the lab's schedule to suggest the closest free time windows on that exact device, and scores other compatible devices in the building that are free right at 10:00.
   - Or, the student can join the **FIFO Waitlist** (#59). The moment the current holder cancels or returns the device early, the system automatically promotes the first eligible student from the waitlist into a confirmed reservation and sends them a notification.
3. **What if a student never shows up, or forgets to return the equipment on time?** Background **Lifecycle Jobs** (#60) automatically release unclaimed bookings after 30 minutes (`NO_SHOW`) and mark late returns as `OVERDUE`, proactively moving the next student's upcoming booking to a substitute asset so they aren't left waiting at the lab door.

---

## Part 2: Technical details

### 1. Dry-Run Maintenance Impact Simulator (`MaintenanceImpactSimulator` — PR #58)

| Aspect | Detail |
|---|---|
| **Endpoint** | `POST /api/v1/admin/reassignments/simulate` (Admin only) |
| **Transaction** | `@Transactional(readOnly = true)` — zero database writes |
| **Algorithm** | Iterates chronologically over active reservations overlapping `[maintenanceStart, maintenanceEnd]`, delegating to the injected `List<SubstituteStrategy>` chain (`SameModelStrategy` `@Order(1)` → `SameCategoryStrategy` `@Order(2)`) while tracking virtual allocations in `Map<Long, List<TimeWindow>>` so two affected bookings are never assigned the same substitute in overlapping slots. |

### 2. FIFO Waitlist with Automatic Promotion (`WaitlistService` — PR #59)

| Aspect | Detail |
|---|---|
| **Table** | `waitlist_entries` (`V2026_10_26_1700__reservation_create_waitlist.sql`) |
| **Endpoints** | `POST /api/v1/reservations/waitlist`, `GET /api/v1/reservations/waitlist/me`, `POST /api/v1/reservations/waitlist/{id}/cancel`, `POST /api/v1/admin/reservations/waitlist/assets/{assetId}/promote` |
| **Concurrency** | Acquires pessimistic lock (`catalogApi.lockForBooking(assetId)`) before scanning `WAITING` entries in `createdAt ASC` order, verifying both `existsOverlap == false` and student active quota `< 2`. |

### 3. Check-Out / Return Windows & Autonomous Lifecycle Jobs (`ReservationLifecycleService` — PR #60)

| Aspect | Detail |
|---|---|
| **Endpoints** | `POST /api/v1/admin/reservations/{id}/check-out`, `POST /api/v1/admin/reservations/{id}/return`, `POST /api/v1/admin/reservations/jobs/process-lifecycle` |
| **Check-out Window** | Allowed only within `[startTime - 15 min, startTime + 30 min]`; otherwise throws `CHECKOUT_WINDOW_CLOSED` (HTTP 409). |
| **Scheduled Jobs** | `ReservationLifecycleScheduler` runs every 5 minutes (`fixedDelayString`): (1) transitions `CONFIRMED` bookings past `startTime + 30m` to `NO_SHOW`; (2) transitions `CHECKED_OUT` bookings past `endTime` to `OVERDUE` and triggers cascade reassignment (`moveToAsset` / `forceCancel`) for any `CONFIRMED` booking starting within the next 30 minutes on the unreturned asset. |

### 4. Sweep-Line Slot & Multi-Criteria Substitute Recommender (`SlotRecommendationService` — PR #61)

| Aspect | Detail |
|---|---|
| **Endpoint** | `GET /api/v1/reservations/recommendations?assetId=&desiredStart=&desiredEnd=` |
| **Sweep-Line Gap Finder** | Advances a cursor across `[08:00, 20:00]`, subtracting occupied intervals and slicing contiguous free gaps into 30-minute aligned candidate windows sorted by `\|candidateStart - desiredStart\|`. |
| **Multi-Criteria Scoring** | Ranks free substitute assets using a deterministic 0–100 score: `+60` (same model) or `+30` (same category), `+30` (same lab room), `+10` (zero active reservations on that date for wear-and-tear balancing). |

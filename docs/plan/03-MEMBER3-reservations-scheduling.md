# Member 3: Reservations & Scheduling Engine Lead

> Read `00-TEAM-HANDBOOK.md` first. This file only covers what is specific to you.
> You own the **core business logic**: preventing double bookings, enforcing limits, and driving the reservation lifecycle (checkout, return, overdue, no-show). Member 4's reassignment engine is built **on top of your `ReservationApi`**, so its correctness depends on yours.

---

## 1. Project in one paragraph

Campus Equipment & Incident Hub: students book lab equipment by time slot (overlaps rejected) and report broken equipment with photos. Damaged assets are locked automatically. An auto-reassignment engine moves future bookings of a broken asset to a substitute, or force-cancels them, and notifies the students. Spring Boot 4.1 / Java 21 backend (modular monolith), React frontend, H2 locally and PostgreSQL on AWS (deployed by Matei).

## 2. What you own

**Package:** `com.campus.reservation` (`api/`, `domain/`, `repository/`, `service/`, `web/`)

| Item | Details |
|---|---|
| Entity | `Reservation` (userId, assetId, originalAssetId, startTime, endTime, status, createdAt, checkedOutAt, returnedAt, cancelledAt, cancelReason, `@Version`) |
| Enum | `ReservationStatus { CONFIRMED, CHECKED_OUT, RETURNED, CANCELLED, FORCE_CANCELLED, OVERDUE, NO_SHOW }` + one constant `ACTIVE_STATUSES = {CONFIRMED, CHECKED_OUT, OVERDUE}` |
| Conflict engine | Overlap detection + row locking so two simultaneous requests can't both win |
| Business rules | Limits, horizon, duration, granularity, opening hours, cancellation and checkout windows (handbook §10.2) |
| Lifecycle | Checkout, return, cancel: transitions **inside the entity** |
| Scheduled jobs | `OVERDUE` marking, `NO_SHOW` marking (+ notifications) |
| Availability | Endpoint giving the busy intervals of an asset in a date range (for the booking UI) |
| Public facade | `ReservationApi` + `ReservationSummary` (used by Member 4's reassignment engine) |
| Seed data | `@Profile("dev") @Order(3) ReservationSeeder` (past, current and future bookings) |
| Docs (lead) | **Reservation state diagram**, **sequence diagram** (booking with conflict check; checkout), **test plan + test report** for the whole team, `docs/modules/reservation.md` |

---

## 3. How your work connects to others

| Who | Relationship |
|---|---|
| **Member 2 (Catalog)** | You call `catalogApi.getAsset()`, `isBookable()`, and **`lockForBooking()`** inside your `@Transactional` booking method. You must not import catalog entities or repositories. |
| **Member 4 (Reassignment)** | Calls your `findUpcomingActiveByAsset`, `isAssetFree`, `moveToAsset`, `forceCancel`. These must be **correct under repeated calls** (SQS may deliver a message twice) and must **re-check conflicts and lock** on the new asset. |
| **Member 4 (Incidents)** | May call `findCurrentCheckout(assetId)` to link an incident to the reservation during which it happened. |
| **Member 4 (Frontend)** | Booking UI (date/time picker + availability), "My reservations", admin checkout/return screen. **Agree the date-time format with them** (§6). Offer to write `frontend/src/api/reservations.ts` + types. |
| **Matei** | Gives you the `Clock` bean (**critical** for every rule you write), `CurrentUser`, `UserApi`, `NotificationService`, `AuditService`, exceptions, `@EnableScheduling`. Ask Matei for dependencies. |

---

## 4. API (draft: confirm in `docs/api-contract.md` in week 2)

| Method & path | Who | Description |
|---|---|---|
| `POST /api/v1/reservations` | student/admin | Body `{ assetId, startTime, endTime }` → 201 `ReservationResponse`. The user comes **from the JWT**, never from the body |
| `GET /api/v1/reservations/me?status=&page=` | logged in | My reservations (upcoming first) |
| `GET /api/v1/reservations/{id}` | owner/admin | Others' reservation → **404** (don't reveal it exists) |
| `POST /api/v1/reservations/{id}/cancel` | owner/admin | Only `CONFIRMED` and before start (admins: any time before checkout) |
| `GET /api/v1/reservations/availability?assetId=&from=&to=` | logged in | Busy intervals `[{start, end}]` of the asset (no names: privacy). Range ≤ 14 days |
| `GET /api/v1/admin/reservations?status=&assetId=&userId=&from=&to=&page=` | admin | Overview for the lab desk |
| `POST /api/v1/admin/reservations/{id}/checkout` | admin | `CONFIRMED` → `CHECKED_OUT` (within the checkout window) |
| `POST /api/v1/admin/reservations/{id}/return` | admin | `CHECKED_OUT`/`OVERDUE` → `RETURNED` |

Error codes you own: `RESERVATION_NOT_FOUND`, `RESERVATION_CONFLICT`, `ASSET_NOT_BOOKABLE`, `RESERVATION_LIMIT_REACHED`, `BOOKING_TOO_FAR_AHEAD`, `BOOKING_IN_PAST`, `INVALID_TIME_RANGE`, `INVALID_DURATION`, `OUTSIDE_OPENING_HOURS`, `SLOT_NOT_ALIGNED`, `CANCELLATION_NOT_ALLOWED`, `CHECKOUT_WINDOW_CLOSED`, `INVALID_RESERVATION_STATUS_TRANSITION`.

---

## 5. Step-by-step guide

### Week 1: Learn and set up
1. Install JDK 21 (Temurin), IntelliJ IDEA, Git. Set your Git name and **GitHub email**.
2. Clone, run, open Swagger and the H2 console. Do the spring.io guides (handbook §14).
3. Extra for you: learn `LocalDateTime`, `Duration`, `Clock`, and how `@Transactional` works (what a transaction and a rollback are).
4. Practice PR (name in README).

### Week 2: Rules & design
1. Write user stories with acceptance criteria in `docs/requirements/reservation.md`. Example:
   > *As a student I want the system to reject a booking that overlaps an existing one so that two people never show up for the same kit.*
   > Given asset 7 is booked 14:00–16:00, when I request 15:00–17:00, then I get 409 `RESERVATION_CONFLICT`; when I request 16:00–17:00, then it succeeds (back-to-back is allowed).
2. **Lead the business-rules discussion** (handbook §10.2) and get the numbers agreed. Also decide: **can one student hold two different assets at the same time?** (Recommended: yes, the limit of 2 active reservations is enough.)
3. Write the `ReservationApi` interface + `ReservationSummary` with Member 4 (handbook §11).
4. Draft the reservation **state diagram** (handbook §10.1) and the **booking sequence diagram**:
   `Student → ReservationController → ReservationService → CatalogApi.lockForBooking → CatalogApi.isBookable → ReservationRepository.existsOverlap → limits → save → AuditService → 201`.

### Week 3: Entity freeze + stub API
1. Migration, e.g. `V2026_10_20_1100__reservation_create_table.sql`:
   ```sql
   CREATE TABLE reservations (
     id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
     user_id BIGINT NOT NULL REFERENCES users(id),
     asset_id BIGINT NOT NULL REFERENCES assets(id),
     original_asset_id BIGINT REFERENCES assets(id),
     start_time TIMESTAMP NOT NULL,
     end_time TIMESTAMP NOT NULL,
     status VARCHAR(32) NOT NULL,
     created_at TIMESTAMP NOT NULL,
     checked_out_at TIMESTAMP,
     returned_at TIMESTAMP,
     cancelled_at TIMESTAMP,
     cancel_reason VARCHAR(255),
     version BIGINT NOT NULL DEFAULT 0,
     CONSTRAINT chk_reservation_time CHECK (end_time > start_time)
   );
   CREATE INDEX idx_reservations_asset_time ON reservations(asset_id, start_time, end_time);
   CREATE INDEX idx_reservations_user_status ON reservations(user_id, status);
   CREATE INDEX idx_reservations_status_end ON reservations(status, end_time);
   ```
   The `users` and `assets` tables must exist first. Your migration timestamp must be **later** than Matei's and Member 2's (handbook §8.2).
2. Entity with **plain IDs** for user/asset (`private Long userId; private Long assetId;`). They cross module boundaries, so no `@ManyToOne` to other modules' entities.
3. **Transitions inside the entity**:
   ```java
   public void checkOut(LocalDateTime now) {
       requireStatus(ReservationStatus.CONFIRMED);
       status = ReservationStatus.CHECKED_OUT;
       checkedOutAt = now;
   }
   public void returnItem(LocalDateTime now) {
       if (status != CHECKED_OUT && status != OVERDUE) throw invalidTransition();
       status = ReservationStatus.RETURNED;
       returnedAt = now;
   }
   public void cancel(LocalDateTime now, String reason) { requireStatus(CONFIRMED); status = CANCELLED; cancelledAt = now; cancelReason = reason; }
   public void forceCancel(LocalDateTime now, String reason) { requireStatus(CONFIRMED); status = FORCE_CANCELLED; … }
   public void moveTo(Long newAssetId) { requireStatus(CONFIRMED); if (originalAssetId == null) originalAssetId = assetId; assetId = newAssetId; }
   ```
4. Merge a **stub** `ReservationApi` implementation so Member 4 can start.

### Week 4: Create, cancel, conflict check (the heart of the project)
1. **Overlap rule.** Two intervals overlap iff `existing.start < new.end AND existing.end > new.start`. Strict `<`/`>` makes back-to-back bookings (14–16 and 16–18) legal.
   ```java
   @Query("""
       select count(r) > 0 from Reservation r
       where r.assetId = :assetId
         and r.status in :statuses
         and r.startTime < :end and r.endTime > :start
         and r.id <> :ignoreId
       """)
   boolean existsOverlap(Long assetId, LocalDateTime start, LocalDateTime end,
                         Collection<ReservationStatus> statuses, long ignoreId);
   ```
   Pass `ignoreId = -1L` when there is nothing to ignore. Don't use `(:ignoreId is null or …)`: null-parameter tricks behave differently on PostgreSQL than on H2.
2. **Booking algorithm** (one `@Transactional` method, order matters):
   1. Validate the input (time range, past, alignment, duration, horizon, opening hours) using `LocalDateTime.now(clock)`.
   2. `catalogApi.lockForBooking(assetId)`: **lock first**, so a second concurrent request for the same asset waits here.
   3. `catalogApi.isBookable(assetId)` → otherwise 409 `ASSET_NOT_BOOKABLE`.
   4. `existsOverlap(...)` with `ACTIVE_STATUSES` → 409 `RESERVATION_CONFLICT`.
   5. Limits: `countByUserIdAndStatusIn(userId, ACTIVE_STATUSES)` ≥ 2 → 409 `RESERVATION_LIMIT_REACHED` (skip for admins).
   6. Save, audit, return 201.
3. **Cancel:** owner or admin; only `CONFIRMED`; students only before `startTime`.
4. **Ownership:** load the reservation; if `!r.getUserId().equals(CurrentUser.id()) && !CurrentUser.isAdmin()` → **404**.
5. **Tests: the overlap matrix.** Write these as `@DataJpaTest` (real SQL) + service unit tests. Existing booking 14:00–16:00:

   | New booking | Expected |
   |---|---|
   | 12:00–13:00 (before) | ✅ |
   | 12:00–14:00 (touches start) | ✅ |
   | 16:00–18:00 (touches end) | ✅ |
   | 13:00–15:00 (overlaps start) | ❌ 409 |
   | 15:00–17:00 (overlaps end) | ❌ 409 |
   | 14:30–15:30 (inside) | ❌ 409 |
   | 13:00–17:00 (contains) | ❌ 409 |
   | 14:00–16:00 (identical) | ❌ 409 |
   | 15:00–17:00 but existing is `CANCELLED` | ✅ |
   | 15:00–17:00 on a different asset | ✅ |
   | end ≤ start / in the past / 15 days ahead / 5h long / 14:10 start | ❌ 400/409 with the right code |

   Use a **fixed `Clock`** in tests (`Clock.fixed(Instant.parse("2026-11-02T08:00:00Z"), ZoneId.of("Europe/Bucharest"))`) so the tests don't break depending on the day you run them.

### Week 5: Rules, "my reservations", availability
1. The remaining rules from handbook §10.2, each with a test.
2. `GET /reservations/me` (upcoming first, then past; paginated).
3. Availability endpoint: active reservations of the asset overlapping `[from, to]` → list of `{start, end}`. Validate `to > from` and range ≤ 14 days.
4. Real `ReservationApi` implementation:
   - `findUpcomingActiveByAsset(assetId, from)`: status `CONFIRMED`, `endTime > from` (include a booking that has started but not yet been picked up), ordered by `startTime`.
   - `isAssetFree(assetId, start, end, ignoreId)`: the same overlap query.
   - `moveToAsset(id, newAssetId, reason)`: `@Transactional`; `catalogApi.lockForBooking(newAssetId)`; re-check `isBookable` + overlap (ignoring itself); `reservation.moveTo(newAssetId)`; audit. If it's no longer free → throw a specific exception that Member 4 catches to try the next candidate.
   - `forceCancel(id, reason)`: only if still `CONFIRMED` (idempotent: already `FORCE_CANCELLED` → do nothing, no error).

### Week 6: Admin checkout / return (M1)
1. Checkout only inside the window (from 15 min before start to 30 min after start, per the agreed numbers) → otherwise 409 `CHECKOUT_WINDOW_CLOSED`.
2. Return from `CHECKED_OUT` or `OVERDUE`.
3. Admin list endpoint with filters (status, asset, user, date range) for the lab-desk screen.
4. `ReservationSeeder` (`@Order(3)`): bookings for yesterday (returned), today (one checked out, one confirmed), tomorrow and next week. Some must be on assets that Member 4 will "break" in the demo.
5. Help Member 4 wire the booking form and "My reservations".

### Week 7: Scheduled jobs
1. Overdue: `@Scheduled(cron = "0 */5 * * * *", zone = "Europe/Bucharest")` → find `CHECKED_OUT` with `endTime < now(clock)` → `OVERDUE` + notification to the student (+ optional admin).
2. No-show: `CONFIRMED` with `startTime + 30 min < now` → `NO_SHOW` (frees the slot).
3. **Idempotent jobs:** only touch rows still in the old status. If prod runs 2 ECS tasks, both run the job, and the second run must find nothing to do. Use `@Version` or a conditional bulk update (`update … set status='OVERDUE' where id=:id and status='CHECKED_OUT'`) and send the notification only when a row was actually changed.
4. **Testing jobs:** don't wait for the scheduler in tests. Call the job method directly with a fixed `Clock`.

### Week 8–9: Hardening + integration with reassignment
1. **Concurrency test:** two threads booking the same slot at the same time (`ExecutorService` + `CountDownLatch` in a `@SpringBootTest`); exactly one must succeed. This test proves the lock works, and it's great for the defense.
2. Test `moveToAsset`/`forceCancel` together with Member 4 (end-to-end: incident → reservation moved).
3. Notifications for cancellations made by admins (optional).
4. M2: everything works locally and on AWS. Check times on AWS (Matei's container is UTC; you use the `Clock`, so they should be right. Verify it).

### Weeks 10–12: Test lead, diagrams, defense
1. You are the **test lead**: write `docs/testing/test-plan.md` (what kinds of tests, which flows, who), collect JaCoCo results, write the **test report**. Make sure every module has tests for its critical paths.
2. Full-flow integration tests: book → checkout → return; book → overdue job → return; book → cancel.
3. Final state diagram + sequence diagrams (match the code!). Report section: the conflict algorithm, transactions + locking, scheduled jobs, state machine.
4. Demo part: show a conflict being rejected, the limit being enforced, checkout/return.

---

## 6. Things to keep in mind

- **Date/time format between frontend and backend (agree with Member 4 in week 2):**
  The backend uses `LocalDateTime` = **campus local time (Europe/Bucharest), no offset**, in JSON as `"2026-11-03T14:00:00"`.
  The frontend **must not** send `date.toISOString()`. That converts to UTC with a `Z` (`"2026-11-03T12:00:00.000Z"`), and the booking shifts by 2 hours or fails to parse. Use `dayjs(date).format('YYYY-MM-DDTHH:mm:ss')`. Write one test that posts the exact JSON the frontend sends.
- **Never call `LocalDateTime.now()`** without the injected `Clock`. On AWS the container runs in UTC.
- **DST:** Romania changes clocks on the last Sunday of March and October. Your rules use opening hours (08:00–20:00), so night-time DST gaps don't matter. Mention it in the report as a considered edge case.
- **Truncate seconds/nanos** from incoming times (or reject non-aligned values), otherwise `14:00:00.123` breaks "touching" bookings.
- **`ACTIVE_STATUSES` is defined once** and used everywhere (conflicts, limits, availability, facade). If someone adds a new status (e.g. `AUTO_REALLOCATED`), it must be considered there. That's how double-bookings sneak in.
- **The user always comes from the JWT** (`CurrentUser.id()`). Never accept `userId` in a student request body.
- **The lock order is lock → check → insert, all in one transaction.** Checking first and locking afterwards doesn't protect anything.
- **`@Transactional` pitfalls:** it doesn't work on `private` methods or when calling a method of the same class (self-invocation). Put the transactional entry points on public service methods called from the controller or the facade.
- **Facade contract:** after week 3, don't rename or remove `ReservationApi` methods; Member 4 depends on them.

---

## 7. Foreseeable problems (yours)

| Problem | Symptom | Fix / prevention |
|---|---|---|
| Off-by-one at interval edges | Back-to-back bookings rejected, or overlaps accepted | Strict `<`/`>`; the overlap test matrix |
| Double booking under load | Two `CONFIRMED` rows for the same slot | `lockForBooking` before the check, in the same transaction; concurrency test |
| Lock does nothing | Concurrency test fails | Not in a transaction; self-invocation; Member 2's method must be `MANDATORY` |
| Time shifted by 2–3 hours | Bookings appear at wrong times / "in the past" errors on AWS | `Clock` everywhere; agreed JSON format; no `toISOString()` |
| Tests pass today, fail tomorrow | Rules depend on "now" | Fixed `Clock` in tests |
| `JSON parse error` on dates | 400 on POST | Agree the format; test with the exact frontend payload |
| Cancelled bookings block slots | Conflict on free slots | Use `ACTIVE_STATUSES` in the query |
| Students see/cancel others' bookings | Security hole (IDOR) | Ownership check → 404; a test with two student users |
| Overdue job marks the same row twice / double emails | Duplicated notifications | Conditional updates; notify only if changed |
| Scheduled job never runs | No `OVERDUE` ever | `@EnableScheduling` missing (Matei), or the job is in a class that isn't a bean |
| Job crashes on one bad row and stops | Others not processed | try/catch per row + log |
| `moveToAsset` races with a new booking on the substitute | Double booking after reassignment | Lock + re-check inside `moveToAsset` |
| Reassignment retried (SQS) → errors | Exceptions on the second run | Idempotent `forceCancel`/`moveTo` (skip if no longer `CONFIRMED`) |
| `OptimisticLockingFailure` when admin and job touch the same reservation | 409 for the admin | Acceptable: the frontend shows "changed, refresh" |
| `ddl-auto=validate` fails | App won't start after your entity change | Write the migration in the same PR as the entity change |
| Rules debated forever | No code by W4 | Rule numbers decided in W2 and written down; change later via `decisions.md` |
| Scope creep (recurring bookings, waitlists) | Behind schedule | Stretch goals only after M2 |

---

## 8. Personal checklist

- [ ] W1: tools + tutorials + `Clock`/`@Transactional` reading + practice PR
- [ ] W2: user stories; business rule numbers agreed; `ReservationApi` agreed; state + sequence drafts
- [ ] W3: migration + entity + transitions + stub facade merged
- [ ] W4: create/cancel + overlap engine + full overlap test matrix
- [ ] W5: all rules; my reservations; availability; real facade
- [ ] W6: checkout/return; admin list; seed data; booking UI wired with Member 4
- [ ] W7: overdue + no-show jobs (idempotent, tested with fixed clock)
- [ ] W8–9: concurrency test; reassignment integration; AWS time check
- [ ] W10–12: test plan + report; integration tests; diagrams; report section; demo part

## 9. What you present at the defense
The reservation state machine, the overlap algorithm and its test matrix, transactions and pessimistic locking (with the concurrency test as proof), scheduled jobs, the facade used by the reassignment engine, and the team's test strategy and coverage.

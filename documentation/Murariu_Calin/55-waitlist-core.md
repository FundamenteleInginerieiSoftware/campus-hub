# 55 — FIFO Waitlist Core (#59, PR 1)

## Part 1: In simple terms

Students can now join a FIFO waitlist for an equipment slot that is already booked or held (up to 5 active entries per student, rejecting duplicate joins and slots the student already booked), inspect their paginated waitlist entries, or cancel a waiting request. When a slot becomes free, `WaitlistService.processPromotionsForAsset` locks the FIFO queue, skips anyone whose active booking limit (2) is full, expires disabled accounts, and promotes the first eligible student into a confirmed reservation.

---

## Part 2: Technical details

| Artifact | Purpose |
|---|---|
| `V2026_11_22_1740__reservation_create_waitlist.sql` | Creates `waitlist_entries` with `CHECK (end_time > start_time)`, partial unique index `uq_waitlist_active_slot` (`WHERE status = 'WAITING'`), FKs, and FIFO/user indexes. |
| `WaitlistEntry.java`, `WaitlistStatus.java` | Domain entity (`@Version`, no `@Setter`) and statuses (`WAITING`, `PROMOTED`, `CANCELLED`, `EXPIRED`, `SKIPPED_QUOTA_FULL`). |
| `WaitlistEntryRepository.java` | `@Lock(PESSIMISTIC_WRITE)` FIFO/user queries (`ORDER BY createdAt ASC, id ASC`), paged `/me` query, and native `expireOldEntries` with `ORDER BY created_at ASC, id ASC FOR UPDATE SKIP LOCKED` and `version = version + 1`. |
| `WaitlistService.java`, `ReservationServiceSupport.java`, `ReservationHoldRepository.java` | `joinWaitlist` (`asset -> userQuota` lock order matching `main`, `404 ASSET_NOT_FOUND` for students on `RETIRED` assets, `WAITLIST_ALREADY_JOINED` checked before 5-entry `WAITLIST_LIMIT_REACHED`), paged `getMyWaitlist`, `cancelWaitlistEntry`, `cancelWaitingEntriesForUser` (without `lockUserQuota`, skips re-enabled accounts), per-candidate `REQUIRES_NEW` `processPromotionsForAsset` (`asset -> waitlist_entries -> userQuota` lock order, system `actorId = null` audit on `PROMOTE_WAITLIST`, shared `MAX_ACTIVE_RESERVATIONS`, overlapping own holds locked `FOR UPDATE ORDER BY id ASC`). |
| `UserDisabledReservationListener.java` | Runs upcoming reservation cancellation FIRST (acquiring `lockUserQuota` to drain in-flight `joinWaitlist` and skipping if re-enabled) and `cancelWaitingEntriesForUser` SECOND (without `lockUserQuota`) in two separate `REQUIRES_NEW` transactions, preventing both orphan `WAITING` rows and disable-vs-promotion deadlocks. |
| `WaitlistController.java`, `JoinWaitlistRequest.java` | `POST /api/v1/reservations/waitlist` (`@JsonFormat(lenient = OptBoolean.FALSE)`), `GET /me` (`PageResponse`), `DELETE /{id}`. |
| `WaitlistServiceTest`, `WaitlistControllerTest`, `WaitlistEntryRepositoryTest`, `WaitlistConcurrencyIT` | Unit, WebMvc (201/204/404 code/Z-offset 400), PostgreSQL `@DataJpaTest` (including `FOR UPDATE SKIP LOCKED` expiry & overlapping hold `FOR UPDATE`), and 5 PostgreSQL concurrency/disable/orphan-drain/deadlock-prevention ITs (`WaitlistConcurrencyIT`). |

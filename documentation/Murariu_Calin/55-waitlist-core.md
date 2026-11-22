# 55 — FIFO Waitlist Core (#59, PR 1)

## Part 1: In simple terms

Students can now join a FIFO waitlist for an equipment slot that is already booked or held (up to 5 active entries per student, rejecting duplicate joins and slots the student already booked), inspect their paginated waitlist entries, or cancel a waiting request. When a slot becomes free, `WaitlistService.processPromotionsForAsset` locks the FIFO queue, skips anyone whose active booking limit (2) is full, expires disabled accounts, and promotes the first eligible student into a confirmed reservation.

---

## Part 2: Technical details

| Artifact | Purpose |
|---|---|
| `V2026_11_22_1740__reservation_create_waitlist.sql` | Creates `waitlist_entries` with `CHECK (end_time > start_time)`, partial unique index `uq_waitlist_active_slot` (`WHERE status = 'WAITING'`), FKs, and FIFO/user indexes. |
| `WaitlistEntry.java`, `WaitlistStatus.java` | Domain entity (`@Version`, no `@Setter`) and statuses (`WAITING`, `PROMOTED`, `CANCELLED`, `EXPIRED`, `SKIPPED_QUOTA_FULL`). |
| `WaitlistEntryRepository.java` | `@Lock(PESSIMISTIC_WRITE)` FIFO/user queries, paged `/me` query, and `expireOldEntries` with `version = version + 1`. |
| `WaitlistService.java`, `ReservationServiceSupport.java` | `joinWaitlist` (under `lockUserQuota`), paged `getMyWaitlist`, `cancelWaitlistEntry`, `cancelWaitingEntriesForUser`, `processPromotionsForAsset` (`asset -> waitlist_entries -> userQuota` lock order + shared `MAX_ACTIVE_RESERVATIONS` + hold confirmation). |
| `UserDisabledReservationListener.java` | Also cancels the disabled user's active `WAITING` entries on `UserDisabledEvent`. |
| `WaitlistController.java`, `JoinWaitlistRequest.java` | `POST /api/v1/reservations/waitlist` (`@JsonFormat(lenient = OptBoolean.FALSE)`), `GET /me` (`PageResponse`), `DELETE /{id}`. |
| `WaitlistServiceTest`, `WaitlistControllerTest`, `WaitlistEntryRepositoryTest`, `WaitlistConcurrencyIT` | Unit, WebMvc (201/204/404 code/Z-offset 400), PostgreSQL `@DataJpaTest`, and 10-thread duplicate-join concurrency IT. |

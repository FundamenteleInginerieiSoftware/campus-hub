# 55 — FIFO Waitlist Core (#59, PR 1)

## Part 1: In simple terms

Students can now join a FIFO waitlist for an equipment slot that is already booked or held, inspect their waitlist entries, or cancel a waiting request. When a slot becomes free, `WaitlistService.processPromotionsForAsset` walks the queue oldest-first, skips anyone whose active booking limit (2) is full, and promotes the first eligible student into a confirmed reservation.

---

## Part 2: Technical details

| Artifact | Purpose |
|---|---|
| `V2026_11_22_1740__reservation_create_waitlist.sql` | Creates `waitlist_entries` table with FKs to `assets`, `users`, `reservations` and FIFO/user indexes. |
| `WaitlistEntry.java`, `WaitlistStatus.java` | Domain entity (`@Version`) and statuses (`WAITING`, `PROMOTED`, `CANCELLED`, `EXPIRED`, `SKIPPED_QUOTA_FULL`). |
| `WaitlistEntryRepository.java` | `findByAssetIdAndStatusOrderByCreatedAtAscIdAsc`, `findByUserIdOrderByCreatedAtDescIdDesc`, `expireOldEntries`. |
| `WaitlistService.java` | `joinWaitlist`, `getMyWaitlist`, `cancelWaitlistEntry`, `processPromotionsForAsset` (`asset -> userQuota` lock order), `expireStaleEntries`. |
| `WaitlistController.java` | `POST /api/v1/reservations/waitlist`, `GET /me`, `DELETE /{id}`. |
| `WaitlistServiceTest.java` | Unit tests covering join, `SLOT_ALREADY_FREE`, `WAITLIST_ALREADY_JOINED`, 404 IDOR cancel, and FIFO promotion + quota skip. |

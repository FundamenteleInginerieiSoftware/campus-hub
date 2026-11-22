# 59 — Next Free Slots Recommender Backend (#61, PR 1)

## Part 1: In simple terms

When a student wants to book a specific equipment unit, `GET /api/v1/reservations/next-free-slots` returns the earliest upcoming 30-minute aligned weekday slots (`08:00`–`20:00` within the 14-day horizon) where that unit is free. Substitute-unit ranking stays in `checkAvailability` (`#286`) with no duplication.

---

## Part 2: Technical details

| Artifact | Purpose |
|---|---|
| `NextFreeSlotsService.java` | `@Transactional(readOnly = true)` scanner finding up to `limit` (1–10) free slots of `durationMinutes` (30–240, step 30), skipping weekends, booked intervals, foreign active holds, and open-ended `OVERDUE` blockers. |
| `ReservationRepository.java`, `ReservationHoldRepository.java` | Added `findByAssetIdAndStatusInOrderByStartTimeAsc` and `findByAssetIdAndStatusAndExpiresAtAfter`. |
| `ReservationController.java`, `NextFreeSlotsResponse.java`, `FreeSlotOption.java` | Added `GET /api/v1/reservations/next-free-slots` endpoint + Swagger docs. |
| `NextFreeSlotsServiceTest.java`, `ReservationControllerTest.java` | Unit tests covering weekend skip, own-hold exclusion, `OVERDUE` open-ended block, `UNDER_MAINTENANCE`, `INVALID_DURATION`, and endpoint JSON contract. |

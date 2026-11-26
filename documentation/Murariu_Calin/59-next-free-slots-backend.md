# 59 — Next Free Slots Recommender Backend (#61, PR 1)

## Part 1: In simple terms

When a student wants to book a specific equipment unit, `GET /api/v1/reservations/next-free-slots` returns the earliest upcoming 30-minute aligned weekday slots (`08:00`–`20:00` within the 14-day horizon) where that unit is free. Substitute-unit ranking stays in `checkAvailability` (`#286`) with no duplication.

---

## Part 2: Technical details

| Artifact | Purpose |
|---|---|
| `NextFreeSlotsService.java`, `ReservationSlotValidator.java` | `@Transactional(readOnly = true)` scanner sharing `OPEN_TIME`, `CLOSE_TIME`, `MAX_ADVANCE_DAYS`, `MIN_DURATION_MINUTES`, `MAX_DURATION_MINUTES`, and `SLOT_STEP_MINUTES` with `ReservationSlotValidator`; finds up to `limit` (1–10) free slots of `durationMinutes` (30–240, step 30), skipping weekends, booked intervals, foreign active holds, and open-ended `OVERDUE` blockers. |
| `ReservationRepository.java`, `ReservationHoldRepository.java` | Added `findByAssetIdAndStatusInOrderByStartTimeAsc` and `findByAssetIdAndStatusAndExpiresAtAfter`. |
| `ReservationController.java`, `NextFreeSlotsResponse.java`, `FreeSlotOption.java` | Added `GET /api/v1/reservations/next-free-slots` endpoint + `@Parameter` Swagger docs + `docs/api-contract.md` table & parameter ranges. |
| `NextFreeSlotsServiceTest.java`, `ReservationControllerTest.java` | One-rule unit tests with `.as(...)` assertions covering weekend skip, foreign vs. own hold, `OVERDUE` open-ended block, exact 14-day horizon edge, `UNDER_MAINTENANCE`/`RETIRED`, `durationMinutes`/`limit` bounds, `ceilToHalfHour` edge cases, and controller `200`/`400` (`Z`/`+02:00`/limit)/`404`/`409` responses. |

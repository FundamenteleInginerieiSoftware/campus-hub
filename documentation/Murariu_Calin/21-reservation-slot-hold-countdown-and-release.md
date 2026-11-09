# 21. 1-Minute Slot Hold Countdown & Live Release in Calendar Booking View

## Part 1: In Simple Terms

When students select an available lab equipment slot in the interactive calendar booking view (`CalendarBookingView`), there is a risk of a race condition: another student might book the exact same slot while they are reviewing details or filling out the form.

This feature introduces an **interactive 1-minute temporary slot hold with a live countdown**:

1. **Explicit Slot Lock (1-Min Hold):**
   - Once a student selects their desired day, start time, and duration, they can click **"Lock slot (1-min hold)"** (`POST /api/v1/reservations/holds` with `assetId`, `startTime`, and `endTime`).
   - The selected slot is held exclusively for 60 seconds. During this time, other students attempting to book or hold that specific slot receive a 409 conflict (`SLOT_HELD`).
   - Unlike locking the entire equipment, holding only the selected slot protects fairness and anti-hoarding: peers can still book earlier or later slots on the same equipment.

2. **Live Countdown Banner & Actions:**
   - The booking confirmation card displays a blue banner with a live second-by-second countdown timer (`Slot locked for you: XXs left`) and an animated progress bar.
   - When the countdown drops below 15 seconds, the banner shifts to red to urge confirmation.
   - The student can click **"Confirm reservation"** (`POST /api/v1/reservations/holds/{id}/confirm`), which promotes the hold to a confirmed reservation in a single atomic step.
   - If the student changes their mind or selects a different slot, they can click **"Release hold"** (or change slots), which immediately frees the slot for other students.
   - If the student navigates away, unmounts the modal, or closes the browser, the component automatically releases the hold to prevent hold leakage.

---

## Part 2: Technical Details

### Architecture & Concurrency Model

```
Student A selects slot (e.g. 14:00-16:00) & clicks "Lock slot (1-min hold)"
  ├──> POST /api/v1/reservations/holds (assetId, startTime, endTime)
  │      └──> Holds specific slot with 1-minute TTL
  │
Student B tries to hold/book the same slot
  ├──> existsActiveOverlap(assetId, start, end, now, null, userIdB) -> TRUE
  └──> Rejects with 409 Conflict (code: "SLOT_HELD")
  │
Student A clicks "Confirm reservation"
  ├──> POST /api/v1/reservations/holds/{id}/confirm
  │      └──> Status transitions ACTIVE -> CONFIRMED, creates Reservation entity
  └──> Reservation created & redirects to /reservations
```

### Files Modified

| File | Change | Description |
|---|---|---|
| `frontend/src/api/reservations.ts` | Modified | Added `CreateHoldRequest`, `ReservationHoldResponse`, `HoldStatus`, and API functions: `createHold`, `confirmHold`, and `releaseHold`. |
| `frontend/src/api/errorMessages.ts` | Modified | Added user-friendly mappings for `ACTIVE_HOLD_LIMIT_REACHED`, `SLOT_HELD`, and `HOLD_NOT_FOUND`. |
| `frontend/src/components/CalendarBookingView.tsx` | Modified | Added slot hold locking action, live 1-second countdown timer with progress bar, unmount safety guard (`isMountedRef`), and release/confirm transitions. |

- **Memory & Lifecycle Safety:**
  - **Unmount Protection (`isMountedRef`):** If the component unmounts while `createHold` is still in flight (e.g., fast modal close or React StrictMode double mount), the `.then` handler checks `isMountedRef.current` and immediately calls `releaseHold(hold.id)`, preventing orphaned holds.
  - **Slot Switch Invalidation:** Selecting a new slot or modifying duration automatically releases the existing hold on the old slot.
  - **Reference Cleanup:** On confirmation, `activeHoldRef.current` is cleared before unmount/redirect so redundant releases are not sent.
  - **Wall-Clock Deadline Timer:** The countdown computes remaining seconds against a wall-clock deadline (`deadlineRef.current = Date.now() + remainingSeconds * 1000`) on each tick, preventing browser tab throttling in inactive tabs from causing timer drift.

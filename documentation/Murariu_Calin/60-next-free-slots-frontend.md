# 60 — Next Free Slots Recommender Frontend (#61, PR 2)

## Part 1: In simple terms

When a student selects an occupied time slot on the booking calendar, **CalendarBookingView** now displays a **"Next free slots on this unit"** card alongside any free substitute units. Clicking any recommended slot jumps the calendar week, day, and start time directly to that open window.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `frontend/src/api/reservations.ts` | Added `FreeSlotOption`, `NextFreeSlotsResponse`, and `fetchNextFreeSlots`. |
| `frontend/src/components/CalendarBookingView.tsx` | Fetches next free slots on `RESERVATION_CONFLICT` and renders one-click jump buttons that align `currentMonday`, `selectedDay`, and `selectedStartTime`. |

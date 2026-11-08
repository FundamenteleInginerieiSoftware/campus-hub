# 20. My Reservations Management & Incident Reporting Shortcut

## Part 1: In Simple Terms

Students managing lab bookings need flexible options when experiments take longer than expected, when group projects require handing off equipment to another teammate, or when hardware malfunctions.

This update introduces complete self-service reservation management directly within the **"My reservations"** dashboard:

1. **Active Reservation Duration Extension:**
   - Active bookings (`CONFIRMED` or `CHECKED_OUT`) can now be extended with a single click.
   - A modal dialog calculates available extensions (+30 min, +1h, etc.) while automatically enforcing the 4-hour total duration cap and the 20:00 laboratory closing time.
   - If subsequent slots conflict, a clear RFC 9457 error alert is shown.

2. **Peer-to-Peer Reservation Transfer Workflow & Student Identity:**
   - Upcoming confirmed bookings can be transferred to a peer student by user ID.
   - **Student Identity Card:** At the top of the dashboard, a highlighted identity card displays the student's own ID (`Your Student ID: #X`) and email, advising them to share this numeric ID with classmates who wish to transfer reservations to them.
   - **Classmate Quick-Pick:** The transfer modal features direct one-click buttons for lab peers (`Student Two (ID: #2)`, `Student One (ID: #1)`), eliminating guesswork and manual entry errors.
   - When a student logs in, an incoming transfer banner at the top of the dashboard alerts them of pending transfer offers, allowing immediate acceptance or rejection.
   - Once accepted, ownership transfers instantaneously, freeing up quota for the initiator.

3. **Multi-Asset Bundle Indicator:**
   - Bookings that were created together as part of an equipment bundle display a distinctive purple `"Bundle booking"` badge.

4. **Direct Incident Reporting Shortcut:**
   - Each reservation row features a `"Report"` action button that links directly to `/incidents?assetId=<id>`, pre-selecting the asset in Ștefania's incident report modal so students can immediately report damaged equipment.

---

## Part 2: Technical Details

### Files Modified

| File | Change | Description |
|---|---|---|
| `frontend/src/api/reservations.ts` | Modified | Added `extendReservation`, `initiateTransfer`, `fetchPendingTransfers`, `acceptTransfer`, `rejectTransfer`, and `ReservationTransferResponse` type. Added optional `bundleId` field on `Reservation`. |
| `frontend/src/api/errorMessages.ts` | Modified | Added friendly user-facing messages for `EXTENSION_CONFLICT`, `CANNOT_TRANSFER_TO_SELF`, `TRANSFER_ALREADY_PENDING`, `TRANSFER_NOT_FOUND`, `RESERVATION_NOT_TRANSFERABLE`, etc. |
| `frontend/src/pages/MyReservationsPage.tsx` | Modified | Added Extend Modal, Transfer Modal with classmate quick-pick, student identity card, incoming pending transfer alert banner, bundle badge, and report incident action shortcut. |

### Domain Constraints Enforced in UI

- **Duration Extension:** `computeExtensionOptions(r)` calculates `Math.min(startMins + 240, 1200) - endMins` to prevent requests that violate the 4-hour max or 20:00 closing time before sending network requests.
- **Transfer Initiation:** Only `CONFIRMED` reservations whose start time has not yet passed in Bucharest wall-clock time can be transferred.
- **Memory & Lifecycle Safety:** The pagination guard `if (newPage === pageNumber) return` is preserved, and state resets on tab/page transitions prevent stuck loaders.

# Reservations desk: scan any student pass, filter by student, and show student names (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-28
**Branch / PR:** `feat/desk-scan-any-pass` (#377)
**See also:** `26-reservations-desk-page.md` (#138), `51-pickup-pass-backend.md` (#283), `52-pickup-pass-pages.md` (#284), `58-pickup-pass-qr.md` (#318)

---

## Part 1: In simple terms

1. **Student names appear right away:** before this change, the Reservations desk showed only `Student #4` until the admin checked that student's pickup code. Now the desk looks up each student's name and e-mail automatically and shows the name on every row from the start; verifying their pass adds the green check on top.
2. **One-click "Scan student pass" in the top bar:** when a student walks up to the lab counter, the admin no longer has to hunt through pages of bookings to find their row first. Clicking **Scan student pass** opens the camera immediately to read any student's pickup pass QR.
3. **Automatic filter to the scanned student:** once the pass verifies, the desk marks that student as verified and narrows both the table and all three tab counters (**To pick up**, **Out now**, **Overdue**) to just that student's bookings, showing a teal banner with their name, e-mail, and ID.
4. **Quick reset and row verify kept:** clicking **Show all students** in the banner brings back the full desk view. Clicking **Verify** on an individual row still checks only that row's student (starts on typing the 6 digits, now titled with their name, and keeps the full table visible).
5. **Fallback without a camera:** if the device has no camera, access is blocked, or the page is not on HTTPS, the dialog explains why in plain words and switches to typing the **Student ID** and **6-digit code** instead.

**Result:** handling a queue at the lab counter takes one scan at the top of the page, immediately showing only that student's bookings with real names across the whole desk.

---

## Part 2: Technical details

### Files changed

| File | Change |
|---|---|
| `frontend/src/api/reservationDesk.ts` | Add optional `userId?: number` to `DeskSearch` and append `userId` to the query string of `GET /api/v1/admin/reservations` |
| `frontend/src/pages/ReservationsDeskPage.tsx` | Add top-bar **Scan student pass** button (`VerifyDialogTarget` union), `filteredStudent` state + teal filter banner with **Show all students** reset, `userId` filtering on both the list effect and the 3 tab-count queries, student-specific empty-state copy, and cached per-id `fetchAdminUser` lookups (`users` state + `requestedUserIds` ref) so real student names display before verification |
| `frontend/src/pages/ReservationsDeskParts.tsx` | Make `VerifyStudentDialog`'s `userId` optional and add optional `studentName`; top-bar mode opens in `scan` by default and adds a numeric `Student ID` input + `Verify` submit button in `type` mode; add `submitting` ref guard, mode-aware focus restoration and error text, and `VerifiedMark` formatting cleanup |
| `frontend/src/pages/ScanPassPanel.tsx` | Make `userId` optional, switch callback from `onCode(code)` to `onScan({ userId, code })`, validate `userId` as a positive safe integer, dedupe repeated scanner frames by `${userId}:${code}`, and pass `anyStudent` to `cameraMessage` so camera fallbacks mention the student ID when needed |

No backend files, `package.json`, `client.ts`, or shared components were changed.

### How the pieces work together

| Piece | What it does and why |
|---|---|
| `VerifyDialogTarget` (`{ kind: 'any' } \| { kind: 'student'; userId: number }`) | Replaces `verifyingUserId: number \| null`. Top-bar clicks open `{ kind: 'any' }` (starts in `Scan QR` for kiosk speed and filters the desk on success); row clicks open `{ kind: 'student', userId }` (starts in `Type code`, passes `users[userId]?.fullName` for the title `Verify <Name> (#id)`, and verifies without filtering away other rows). |
| `searchReservations({ status, userId, date, page, size })` | Passes `userId` only when defined, keeping `sortDir=asc`. Used by both `searchFor` (active tab rows) and the counts effect (`size=1` for `pickup`, `out`, `overdue`), so the tab badges always match the filtered student. |
| Parallel asset + student caching (`requestedAssetIds` / `requestedUserIds`) | After a page loads, uncached `assetId`s and `userId`s are deduplicated and fetched in parallel (`fetchAssetSummary` + `fetchAdminUser`). Both use refs rather than state because they are read after `await` inside the effect; completed names are kept in state even if the admin switches tabs meanwhile, matching the `#166` caching pattern. |
| Student column rendering | Renders `VerifiedMark` (name + teal check) when `verified[r.userId]` exists, otherwise `users[r.userId].fullName` (with e-mail tooltip and dimmed `#id`), falling back to `Student #<id>` while loading or if the lookup failed. |
| Filter banner & empty states | When `filteredStudent` is set, a teal `Alert` shows `Filtered to <fullName> (<email>) · #<userId>` with a **Show all students** button, and empty messages name the student (e.g. `No pick-ups for <fullName> on this day.`). |

### Connection to teammates' modules

| Teammate | Module / file used | How this PR uses it |
|---|---|---|
| **Calin** | `reservation` (`AdminReservationController`, `GET /api/v1/admin/reservations`, #80/#172) + `frontend/src/api/reservations.ts` (`fetchAssetSummary`) | Sends the existing `userId` query parameter alongside `status`, `date`, and `sortDir=asc` to filter both the desk table and the three tab-count queries server-side. |
| **Stefania** | `user/admin` (`AdminUserController`, `GET /api/v1/admin/users/{id}`, #206) + `frontend/src/api/adminUsers.ts` (`fetchAdminUser`, `AdminUser`) | Fetches each uncached student's `fullName` and `email` so the Student column and row-level Verify dialog title show real names before a pass is verified. |
| **Liviu** | `catalog` (`GET /api/v1/assets/{id}`, via Calin's `fetchAssetSummary`) | Fetched in parallel with `fetchAdminUser` using the same ref-backed cache pattern. |
| **Matei** | `user` (`AdminPickupPassController`, `POST /api/v1/admin/pickup-pass/verify`, #283) + `frontend/src/api/pickupPass.ts` (`verifyPickupPass`, `parseQrText`) | Verifies either the QR's `{ userId, code }` pair or the manually typed student ID and 6-digit code, writing the `PICKUP_PASS_VERIFIED` audit entry on the backend and returning `VerifiedStudent`. |

### Edge cases handled

| Case | How it is handled |
|---|---|
| **Camera unavailable in kiosk mode** | `ScanPassPanel` formats all 5 `IScannerError` kinds with `enter the student ID and 6-digit code` when `userId === undefined` (vs `type the 6-digit code` on a row) and calls `onUnavailable`, which displays the alert and switches `VerifyStudentDialog` to `type` mode with the `Student ID` `TextInput` (strips non-digits `\D+` on type/paste) and an explicit `Verify` button. |
| **Focus restoration after error** | After a failed check in `type` mode, `useEffect` focuses the `Student ID` box if it is empty or invalid (`parsePositiveUserId`), otherwise focuses the first `PinInput` digit so the admin can re-type immediately. |
| **Double submit in one render tick** | `PinInput` auto-submits on the 6th digit (matching PR 2, since pasted codes did not trigger `onComplete`), while wrapping the modal in `<form>` also enables Enter / clicking **Verify**. A `submitting` ref guards `submit` synchronously so auto-submit + Enter cannot fire two requests before `busy` re-renders. `close()` also returns early while `busy` is true. |
| **Wrong ID vs wrong code error text** | On `409 PICKUP_CODE_INVALID`, manual any-student entry (`isAnyStudent && !fromScan`) tells the admin to check both the student ID and the code; QR scans and row-level verify keep the shorter code-only message. Switching between `Type code` and `Scan QR` clears any stale error. |
| **Scanner frame dedupe & malformed IDs** | `ScanPassPanel` rejects non-positive or non-safe-integer `scanned.userId`, keeps the row-level `scanned.userId !== userId` rejection when `userId` is set, and keys `lastSent` on `${scanned.userId}:${scanned.code}` so scanning a different student immediately works while repeated camera frames of an expired pass stay suppressed until the next 30 s rotation. |
| **Re-scanning the already filtered student** | In `handleVerified`, scanning the student who is already active leaves `filteredUserId` unchanged, so the React effects would not re-run and `loading` would stay `true`. Detecting `sameStudent` and incrementing `reloadToken` forces both effects to reload and clear `loading`. |
| **Transient `fetchAdminUser` failure** | On error, the failed id is removed from `requestedUserIds.current` and the row falls back to `Student #<id>`, so the next reload or tab switch retries fetching that user's name automatically. |
| **Resetting the filter banner** | `clearStudentFilter()` clears `filteredStudent`, resets `pageNumber` to `0`, clears `pageData` (preventing stale filtered rows/buttons from staying clickable during load), and sets `loading = true`, refreshing both the table and all three tab counts. |

### How it was tested

| Command | Result |
|---|---|
| `cd frontend && npm ci` | `added 60 packages, and audited 61 packages in 8s`, `found 0 vulnerabilities` |
| `cd frontend && npm run lint` (`oxlint`) | `Found 0 warnings and 0 errors. Finished in 44ms on 70 files with 116 rules using 12 threads.` |
| `cd frontend && npm run build` (`tsc -b && vite build`) | `✓ 7279 modules transformed.`, `✓ built in 666ms` |
| `git grep -n "ScanPassPanel\|VerifyStudentDialog\|searchReservations" frontend/` | All callers updated cleanly (no stale `onCode` or old signature calls left) |

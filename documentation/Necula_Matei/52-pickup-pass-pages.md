# Pickup pass, pages: the student's code and the desk check (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-11
**Branch / PR:** `feat/frontend-pickup-pass` (#PICKUP2), PR 2 of 3 (the QR image is PR 3)
**See also:** `51-pickup-pass-backend.md`, `docs/superpowers/specs/2026-11-10-pickup-pass-design.md` (section 7)

---

## Part 1: In simple terms

1. **The student opens "Pickup pass"** in the side menu (group "Mine"). The page shows a 6-digit number, for example `493 027`, and a ring that counts down the seconds.
2. **When the ring reaches 0**, the page asks the server for the next number by itself. If the phone was locked and the student comes back, the page also asks at once, so the number on screen is never old.
3. **At the desk**, the admin opens "To pick up", clicks **Verify** on the booking, and types the 6 digits the student reads out.
4. **Right code:** the dialog closes and the Student column shows the real name with a green check, on every row of that student.
5. **Wrong or old code:** the dialog says "The code is wrong or has expired. Ask the student for the new one." and empties the boxes to type again.
6. **Verify is optional.** Check out works with or without it.

**Result:** the desk knows that the person in front of them is the owner of the booking, without a QR scanner yet.

---

## Part 2: Technical details

### Files

| File | What it is |
|---|---|
| `api/pickupPass.ts` | `fetchPickupPass()`, `verifyPickupPass(userId, code)`, `parseQrText(text)` (only `campus-pickup:1:<id>:<6 digits>`; used by PR 3) |
| `pages/PickupPassPage.tsx` | The student page |
| `pages/ReservationsDeskParts.tsx` | `VerifyStudentDialog` (Mantine `Modal` + `PinInput`) and `VerifiedMark`, outside the desk page so that file grows by ~25 lines |
| `pages/ReservationsDeskPage.tsx` | `verified` state (by user id), a Verify button next to Check out, the Student column shows the name once verified |
| `App.tsx`, `layout/navigation.ts` | Route `pickup-pass` and one menu entry (Stefania reviews these two) |

### How the page stays fresh

| Moment | What happens | Why |
|---|---|---|
| Open | One `GET /users/me/pickup-pass`; seconds copied from `validForSeconds` | The backend decides when a code ends, the page only counts |
| Every second | A 1 s interval lowers a ref and the state | The ref is read inside the tick, so no fetch starts inside a `setState` updater (React may run updaters twice) |
| Reaches 0 | The timer stops and `reloadToken` goes up, which refetches | A new `pass` object restarts the timer |
| Tab hidden then visible | `visibilitychange` refetches | Browsers slow down timers in hidden tabs, the number could be old |
| Error | `ErrorAlert` + "Try again"; the timer stays stopped | No retry loop against a down backend |
| Leave the page | Interval and listener are removed; a late answer is ignored (`ignore` flag) | No updates on an unmounted page |

### Verify dialog

- `PinInput length={6} type="number"`; whitespace is stripped before sending, so a pasted `493 027` works.
- The check runs in `onChange` when 6 digits are there. `onComplete` did not fire for a pasted value in the manual test.
- `PICKUP_CODE_INVALID` shows the text above and empties the input; any other error goes through `errorMessage`.
- `verified` lives only in the desk page state: it is a convenience for the person at the desk, not a record. The record is the `PICKUP_PASS_VERIFIED` audit row the backend writes.

### Tested by hand (headless Chromium, real backend on a scratch PostgreSQL)

| Check | Result |
|---|---|
| Code refreshes at 0 | A new request after the countdown, a different code |
| Hidden then visible | Exactly one extra request |
| Wrong code `000000` | Error shown, input emptied (`pickup-04-invalid.png`) |
| Pasted `429 973` (the student's current code) | Dialog closed, "Student One" + green check (`pickup-03-verified.png`) |

Screenshots: `docs/screenshots/pickup-01-pass.png` (390 px wide), `pickup-02-verify-dialog.png`, `pickup-03-verified.png`, `pickup-04-invalid.png`. `npm run lint && npm run build` are clean.

### Still open

PR 3 adds the QR image and a camera scan at the desk; it needs a QR library, so it waits for Stefania to approve one (`package.json` is hers).

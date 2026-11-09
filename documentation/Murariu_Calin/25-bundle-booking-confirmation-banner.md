# 25. Bundle Booking Confirmation Banner in My Reservations

**Author:** Calin Murariu (@Swiorx)  
**Date:** 2026-11-09  
**Branch:** `feat/reservation-bundle-confirmation-banner`  
**Refs:** #140, #243

---

## Part 1: In simple terms

When a student books a single equipment slot, they are redirected to the *My reservations* page with a green banner confirming the reservation ID.

However, when booking a multi-item package or project kit ("Reserve all" in the Projects assistant), `bookBundle` returns a single bundle identifier instead of an individual reservation number. Previously, this redirected to *My reservations* without any top notification banner.

This update teaches the *My reservations* page to recognize `bookedBundleId` and `bundleItemCount` from the navigation state, displaying a clear green confirmation banner:
> "Bundle booking confirmed! 2 items have been added to your upcoming bookings."

---

## Part 2: Technical details

### Frontend State Detection in `MyReservationsPage.tsx`
- Expanded `location.state` type definition to `{ bookedId?: number; bookedBundleId?: string; bundleItemCount?: number } | null`.
- If `state.bookedBundleId` is present:
  - Calls `window.history.replaceState({}, document.title)` to prevent stale banners on page refreshes.
  - Returns a dynamic confirmation message reflecting `bundleItemCount` (or a fallback text if item count is unspecified).
- Cleanly integrates with `ProjectDetailsPage.tsx` or any multi-asset booking flow navigating to `/reservations`.

### Verification
- `cd frontend && npm run lint && npm run build` (0 warnings, 0 errors).

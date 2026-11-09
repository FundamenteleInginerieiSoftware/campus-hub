# 22. Catalog Quick Book Modal & Asset Details Report Button

## Part 1: In Simple Terms

In the initial frontend prototype, booking equipment required navigating to `/catalog/:id`, while reporting damaged equipment was not directly accessible from the hardware details view.

This update resolves both user experience gaps across the catalog and asset details views:

1. **Quick Book Modal directly on `/catalog`:**
   - Every available lab equipment card now includes a **"Quick Book"** action button alongside the "Details" button.
   - Clicking "Quick Book" opens an overlay modal with the Google Calendar-style `CalendarBookingView` pre-loaded for that asset.
   - Students can inspect slot availability, select a time window, and confirm their booking without leaving the catalog or losing their search filters and pagination state.

2. **"Report a Problem" Button on Asset Details (`/catalog/:id`):**
   - Fulfills the remaining integration item from the M1 plan (providing a direct incident report trigger for hardware).
   - Non-retired assets prominently display a **"Report a problem"** button in the header group.
   - Clicking it navigates directly to `/incidents?assetId=<id>`, pre-selecting the asset in the report form modal.

---

## Part 2: Technical Details

### Files Modified

| File | Change | Description |
|---|---|---|
| `frontend/src/pages/CatalogPage.tsx` | Modified | Added `Quick Book` action button on equipment cards, `quickBookAsset` modal state, and mounted `CalendarBookingView` inside an overlay `Modal`. |
| `frontend/src/pages/AssetDetailsPage.tsx` | Modified | Added `Report a problem` button linking to `/incidents?assetId=${asset.id}` for non-retired assets. |

### Module & Component Integration

- **Zero Coupling Violation:** `CalendarBookingView` is cleanly reused by passing the expected `AssetSummary` props contract (`id`, `name`, `model`, `serialNumber`, `status`, `categoryId`, `labRoomId`).
- **SPA State Preservation:** On successful booking from the modal, navigation directs to `/reservations` with `bookedId` preserved in React Router history state.

# 27 — Frontend Audit Fixes: Asset Details State, Audit Log Filters & Modal Guards

**Issue:** Refs [#363](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/363) (PR 2 of 2: Frontend)  
**Date:** 27 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

This PR finishes the frontend fixes from Matei's audit (#363):

1. **No stale asset details & strict ID check (`AssetDetailsPage.tsx`):** Navigating from one equipment details page to another (including a missing/retired ID that returns 404) now clears the previous asset immediately so old details never linger on screen. Invalid URLs like `/catalog/ ` or `/catalog/-1` are rejected before making an API request.
2. **Audit log filters & incident links (`AdminAuditLogPage.tsx`):** Added `CALENDAR_FEED_CREATED` and `CALENDAR_FEED_REVOKED` to the action filter dropdown, made `INCIDENT` entries clickable links to `/incidents/:id`, and added an inline `From date <= To date` validation check.
3. **Locked modals while submitting:** All create/edit/delete/retire modals in the inventory tabs and asset details page now ignore backdrop clicks, Escape, and close-button clicks while a save/delete/retire request is in flight.

---

## Part 2: Technical Details

### 1. Files Changed
- **`frontend/src/pages/AssetDetailsPage.tsx`:**
  - Replaced `!isNaN(Number(id))` with `/^\d+$/.test(id) && Number(id) > 0`.
  - Cleared `asset` (`setAsset(null)`) at the start of the load `useEffect` and in `.catch(...)`.
  - Guarded the retire confirmation `<Modal>` (`onClose`, `closeOnClickOutside`, `closeOnEscape`, `withCloseButton`) with `!retireLoading`.
- **`frontend/src/pages/AdminAuditLogPage.tsx`:**
  - Added `CALENDAR_FEED_CREATED` and `CALENDAR_FEED_REVOKED` to `ACTION_OPTIONS`.
  - Linked `INCIDENT` entity rows to `/incidents/${row.entityId}`.
  - Added `dateRangeInvalid` (`fromDate > toDate`) inline error on `To date` and skipped the search fetch while invalid.
- **`frontend/src/pages/catalog/InventoryAssetsTab.tsx`**, **`InventoryCategoriesTab.tsx`**, **`InventoryLabRoomsTab.tsx`:**
  - Guarded all 6 create/edit/retire/delete modals with `!loading` on `onClose`, `closeOnClickOutside`, `closeOnEscape`, and `withCloseButton`.

### 2. Verification
- `cd frontend && npm run lint && npm run build` — 0 warnings, 0 errors, clean production bundle.

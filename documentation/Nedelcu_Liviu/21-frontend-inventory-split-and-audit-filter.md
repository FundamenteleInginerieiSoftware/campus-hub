# 21 — Frontend Inventory Page Split, Shared Status Badge Helper, and Pickup Pass Audit Filter

**Issues:** Refs [#268](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/268), [#283](https://github.com/FundamenteleInginerieiSoftware/campus-hub/pull/283)  
**Date:** 16 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

Following the backend cleanup for Issue #268, three frontend improvements were completed in the catalog and audit log pages:

1. **Splitting `AdminInventoryPage.tsx`:** Previously, all three tabs (Equipment Assets, Categories, Lab Rooms) and all six modals lived in one 889-line component with ~25 `useState` hooks and a single shared `modalError` (meaning an error triggered in one modal could still appear if the user opened a different modal). Each tab now lives in its own focused component with isolated modal error state.
2. **Extracting `statusBadgeColor`:** The status-to-color mapping for `AVAILABLE`, `UNDER_MAINTENANCE`, and `RETIRED` was duplicated across `CatalogPage`, `AssetDetailsPage`, and `AdminInventoryPage`. It now lives in one shared helper.
3. **Pickup pass audit filter:** Added `PICKUP_PASS_VERIFIED` (from Matei's pickup pass backend #283) to the action dropdown in `AdminAuditLogPage.tsx`.

---

## Part 2: Technical Details

### 1. Inventory Tab Components (`frontend/src/pages/catalog/`)
- [`InventoryAssetsTab.tsx`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/frontend/src/pages/catalog/InventoryAssetsTab.tsx): Manages asset search, pagination, create/edit asset modal (`assetModalError`), and retire confirmation modal (`retireModalError`).
- [`InventoryCategoriesTab.tsx`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/frontend/src/pages/catalog/InventoryCategoriesTab.tsx): Manages categories table, create/edit category modal (`formModalError`), and delete confirmation modal (`deleteModalError`).
- [`InventoryLabRoomsTab.tsx`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/frontend/src/pages/catalog/InventoryLabRoomsTab.tsx): Manages lab rooms table, create/edit room modal (`formModalError`), and delete confirmation modal (`deleteModalError`).
- [`AdminInventoryPage.tsx`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/frontend/src/pages/AdminInventoryPage.tsx): Reduced to a ~75-line orchestrator holding shared category/room lookups and tab state.

### 2. Shared Status Badge Color Helper
- Created [`assetStatus.ts`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/frontend/src/pages/catalog/assetStatus.ts) exporting `statusBadgeColor(status: AssetStatus)`.
- Reused in `CatalogPage.tsx`, `AssetDetailsPage.tsx`, and `InventoryAssetsTab.tsx`.

### 3. Audit Log Action Filter
- Updated `ACTION_OPTIONS` (`Users` group) in [`AdminAuditLogPage.tsx`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/frontend/src/pages/AdminAuditLogPage.tsx) to include `PICKUP_PASS_VERIFIED`.

### 4. Verification
- `npm run lint` (`oxlint`): 0 warnings, 0 errors.
- `npm run build` (`tsc -b && vite build`): succeeded cleanly.

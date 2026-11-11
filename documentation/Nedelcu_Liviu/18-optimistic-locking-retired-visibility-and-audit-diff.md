# 18 — Optimistic Locking, Retired Asset Visibility, and Detailed Audit Diffs

**Issue:** [#268](https://github.com/FundamenteleInginerieiSoftware/campus-hub/issues/268)  
**Date:** 10 November 2026  
**Author:** Liviu Nedelcu (`@NliviuN`)

---

## Part 1: In Simple Terms

During a comprehensive code read-through of the catalog and shared services, four targeted improvements were identified and addressed:

1. **Preventing Concurrent Lost Updates:** Previously, if two administrators opened the edit modal for the same physical asset and made changes simultaneously, whoever saved last would silently overwrite the other admin's modifications. Now, the application carries the entity `version` throughout the edit flow; if the asset has already been updated in the background, the server rejects the outdated edit with `409 Conflict` (`STALE_VERSION`), prompting the user to refresh and review the latest changes.
2. **Consistent Retired Asset Visibility:** In search and inventory views, retired equipment is hidden from students. However, accessing `/api/v1/assets/{id}` directly previously returned the retired asset regardless of user role. Now, non-administrative students attempting to access retired equipment receive a `404 Not Found`, keeping asset details aligned with catalog search.
3. **Comprehensive Audit Logs:** Previously, editing an asset logged only `"Updated asset <name>"`. The audit entry now records exact before-and-after differences (e.g. name, model, room change, or category move) to provide full visibility into administrative operations.
4. **Eliminating Stale Detail Flashes:** Navigating between equipment details pages previously kept the previous asset visible until the new asset finished loading. The details page now resets its active state immediately when the route parameter changes.

---

## Part 2: Technical Details

### 1. Optimistic Locking Implementation
- **DTOs:** Added `Long version` to `AssetResponse` and `UpdateAssetRequest` (with backward-compatible overloaded constructor).
- **Frontend:** Updated TypeScript `Asset` and `UpdateAssetRequest` types in `src/api/catalog.ts` and passed `version: editingAsset.version` from `AdminInventoryPage.tsx`.
- **Validation:** In `AssetService.update(id, request)`, if `request.version()` is provided and differs from `asset.getVersion()`, the service throws `ObjectOptimisticLockingFailureException(Asset.class, id)`.
- **Error Handling:** `GlobalExceptionHandler` maps this to RFC 9457 `ProblemDetail` with status `409 Conflict` and error code `STALE_VERSION`.

### 2. Retired Asset Access Control
- In `AssetService.getById(id)`, after fetching the asset, checked:
  ```java
  if (asset.getStatus() == AssetStatus.RETIRED && !currentUser.isAdmin()) {
      throw new NotFoundException("ASSET_NOT_FOUND", "Asset " + id + " was not found");
  }
  ```
- Aligns direct endpoint access with AGENTS.md §5 ("Others' resources return 404, not 403").

### 3. Detailed Audit Diffs
In `AssetService.update(id, request)`, field changes are tracked dynamically:
- Name changes: `name: 'Old' -> 'New'`
- Model changes: `model: 'Old' -> 'New'`
- Category changes: `category: CatA -> CatB`
- Laboratory changes: `room: RoomA -> RoomB`
- Description changes: `description updated`
- Log format: `"Updated asset <name> (<changes>)"` or `"Updated asset <name> (no fields changed)"`.

### 4. Frontend Route Transition
In `AssetDetailsPage.tsx`, the `useEffect` resets loading and error states in a microtask before executing `getAsset(assetId)` and finalizes with `.finally(() => setLoading(false))`, preventing stale flashes across route changes.

# 15 — Catalog Search Spinner, Audit Filters & Dev Seeder Fixes (#230)

**Author:** Liviu Nedelcu (`@NliviuN`)  
**Date:** 2026-11-09 (W6)

---

## Part 1: In simple terms (for non-programmers)

During testing on the full application, three edge-case issues were discovered:
1. **Catalog Search Spinner:** In the equipment catalog, if a student quickly typed a character and hit Backspace within 300 ms, the loading spinner would get stuck indefinitely with an empty card grid. This happened because the input change event immediately turned on the spinner, but the search query had not changed after the debounce window, so no network fetch was launched to turn it off.
2. **Missing Audit Log Filters:** New reservation workflows (multi-asset bundles, booking duration extensions, 1-minute holds, and peer-to-peer transfers) recorded entries in the audit trail, but the administrative filter dropdowns did not include those actions or entity types. Furthermore, entering an invalid non-numeric ID in the Actor or Entity filter did not display any visual indication.
3. **Seeded Asset 14 Status:** Dev equipment #14 (`ESP32-S3 DevKit 2`) was initialized as `UNDER_MAINTENANCE` without an associated incident report in the database, meaning it could not be transitioned back to `AVAILABLE` through the administrator UI.

This change fixes all three issues:
- The catalog loading spinner is activated only when a network fetch actually begins.
- All new reservation actions and entity types are added to the admin audit log filters, with visual validation hints on numeric ID inputs.
- Asset #14 is seeded as `AVAILABLE` so it can be reserved or used for manual incident reporting demonstrations.

---

## Part 2: Technical details

### Files modified

| File | Changes |
|---|---|
| `frontend/src/pages/CatalogPage.tsx` | Removed synchronous `setLoading(true)` from `TextInput.onChange`; activated loading inside `useEffect` via microtask before launching `searchAssets`. |
| `frontend/src/pages/AdminInventoryPage.tsx` | Removed `setAssetsLoading(true)` from `TextInput.onChange`; aligned loading activation inside the search effect via microtask. |
| `frontend/src/pages/AdminAuditLogPage.tsx` | Added missing entity types (`ReservationBundle`, `ReservationHold`, `ReservationTransfer`) and actions (`CREATE_BUNDLE_RESERVATION`, `EXTEND_RESERVATION`, `HOLD_ACQUIRED`, `HOLD_RELEASED`, `TRANSFER_INITIATED`, `TRANSFER_ACCEPTED`, `TRANSFER_REJECTED`, `TRANSFER_CANCELLED`); configured badge colors; added `error` validation hints on `actorIdInput` and `entityIdInput`. |
| `backend/src/main/java/com/campus/catalog/service/CatalogSeeder.java` | Seeded asset #14 (`ESP32-S3 DevKit 2`) as `AVAILABLE` without calling `markUnderMaintenance`. Note: because `CatalogSeeder` skips an already populated database, existing local databases retain asset #14 under maintenance until a local database reset (`AGENTS.md` §10). |
| `backend/src/test/java/com/campus/catalog/service/CatalogSeederTest.java` | Updated assertions to expect 17 available assets and 0 under maintenance. |

### Verification
- `cd frontend && npm run lint && npm run build` passed with 0 warnings and 0 errors.
- `cd backend && ./gradlew spotlessApply build` passed (`BUILD SUCCESSFUL`).

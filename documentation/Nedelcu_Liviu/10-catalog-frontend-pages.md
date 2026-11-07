# 10. Catalog & Inventory Frontend Pages

## Part 1: In Simple Terms

Before this milestone, the catalog, categories, lab rooms, and assets could only be interacted with via REST API endpoints or Swagger UI. A student who wanted to see what equipment exists in a lab had to read raw JSON responses.

This milestone delivers the complete visual user experience for the catalog module:

1. **Equipment Catalog (`/catalog`):**
   - An intuitive storefront for laboratory hardware.
   - Students and staff can search by text (name, model, serial number) and filter by category (e.g. Oscilloscopes), lab room (e.g. A101), or operational status (`AVAILABLE`, `UNDER_MAINTENANCE`, `RETIRED`).
   - Results are displayed as clean cards with color-coded status badges (Teal for Available, Yellow for Under Maintenance, Gray for Retired).
   - Paginated browsing ensures smooth performance even with hundreds of lab items.

2. **Asset Details Page (`/catalog/:id`):**
   - Dedicated page showing full technical details, serial number, and physical laboratory location.
   - **Seamless Teammate Integration:** Integrates Călin's `CalendarBookingView` component directly on the page, allowing students to inspect availability and book time slots without switching screens.
   - Clear maintenance alerts inform users if the hardware is undergoing repairs.
   - Lab administrators get a direct "Retire Equipment" action button to safely decommission damaged or deprecated hardware.

3. **Admin Inventory Management (`/admin/inventory`):**
   - A comprehensive administrative workspace restricted to `ADMIN` users.
   - Organized into 3 tabs:
     - **Equipment Assets:** Paginated table with real-time search, quick edit modal, and safe retirement workflow.
     - **Categories:** Add, edit, and delete equipment categories.
     - **Lab Rooms:** Add, edit, and delete university lab locations and building designations.
   - Modal forms validate inputs and display clear business-rule messages if a name or serial number is already in use.

---

## Part 2: Technical Details

### Components & Routes

| Path / File | Route | Roles | Primary Responsibilities |
|---|---|---|---|
| `frontend/src/api/catalog.ts` | — | All | Type definitions (`Asset`, `Category`, `LabRoom`, `AssetSearch`) and REST API client functions using `apiFetch`. |
| `frontend/src/api/errorMessages.ts` | — | All | Catalog-specific error code translations (`SERIAL_NUMBER_TAKEN`, `CATEGORY_NAME_TAKEN`, `ASSET_RETIRED`, etc.). |
| `frontend/src/pages/CatalogPage.tsx` | `/catalog` | `STUDENT`, `ADMIN` | Search, multi-criteria filtering, paginated grid display with Mantine cards and status indicators. |
| `frontend/src/pages/AssetDetailsPage.tsx` | `/catalog/:id` | `STUDENT`, `ADMIN` | Asset specifications, slot for Călin's `CalendarBookingView`, maintenance notification banner, retirement modal. |
| `frontend/src/pages/AdminInventoryPage.tsx` | `/admin/inventory` | `ADMIN` only | Tabbed inventory administration (`equipment`, `categories`, `rooms`) with CRUD modals and `PagedTable`. |
| `frontend/src/App.tsx` | App Routes | — | Replaces Liviu's inventory placeholder with `AdminInventoryPage`; wires `/catalog` and `/catalog/:id`. |

### Integration with Teammate Modules

1. **Auth & Security (Matei & Ștefania):**
   - Uses `useAuth()` to determine user role (`ADMIN` vs `STUDENT`).
   - Route `/admin/inventory` is nested within `<RequireAdmin />`, preventing unauthorized student access and redirecting to the catalog.
   - All API requests automatically pass the stored JWT bearer token via `apiFetch`.
2. **Reservations & Booking (Călin):**
   - `AssetDetailsPage` imports and mounts `CalendarBookingView`.
   - Passes the loaded `Asset` object matching the expected `AssetSummary` prop contract (`id`, `name`, `model`, `serialNumber`, `status`, `categoryId`, `labRoomId`).
   - If the asset is `UNDER_MAINTENANCE` or `RETIRED`, booking interactions are cleanly disabled with an informative message.

### UI & UX Best Practices

- **Zero Linting / Type Errors:** Strict compliance with TypeScript strict mode, Oxlint rules, and Mantine Core 9 APIs.
- **Reactive State Updates:** Uses cancellation flags (`let active = true`) inside `useEffect` hooks to prevent memory leaks and race conditions on rapid search typing.
- **RFC 9457 Error Handling:** Errors returned by the Spring Boot backend (`ProblemDetail`) are intercepted and converted into user-friendly messages via `ErrorAlert` and `errorMessages.ts`.

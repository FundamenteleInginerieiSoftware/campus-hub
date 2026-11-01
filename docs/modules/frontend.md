# Frontend (`frontend/`)

**Owner:** Stefania (`@m1runa-stefan1a`). She reviews every frontend PR.
**Stack:** React + Vite + TypeScript, UI kit [Mantine](https://mantine.dev), React Router, icons from [Tabler](https://tabler.io/icons). The look follows `docs/prototype/campus-hub-redesign.html`.

## Purpose
The web app for students and admins. Each member builds the pages of their own module on top of the shared parts below (decision 2026-10-21).

## Run it

```bash
cd backend && ./gradlew bootRun        # backend on :8080 (PostgreSQL running)
cd frontend && npm ci && npm run dev   # app on http://localhost:5173
```

In dev, Vite forwards every `/api/...` call to `localhost:8080`, so there is no CORS setup (ADR-002). On the login page, the dev account buttons fill in `student1`, `student2` or `admin` with password `password`.

Before every push: `npm run lint && npm run build` (CI runs the same two commands).

## Folders

| Folder | What goes there | Owner |
|---|---|---|
| `src/api/client.ts` | `apiFetch()`: adds the token, turns errors into `ApiError` | Stefania (shared) |
| `src/api/errorMessages.ts` | Friendly text per error `code` | everyone adds their module's codes |
| `src/api/types.ts` | Shared shapes (`PageResponse<T>`) | Stefania (shared) |
| `src/api/<module>.ts` | Your module's calls and types, e.g. `src/api/catalog.ts` | module owner |
| `src/auth/` | `useAuth()`, `RequireAuth`, `RequireAdmin` | Stefania (shared) |
| `src/layout/` | Side menu, top bar, `navigation.ts` (menu per role) | Stefania (shared) |
| `src/components/` | Shared components: `PageHeader`, `PagedTable`, `ErrorAlert` | Stefania (shared) |
| `src/pages/` | One file per page, e.g. `CatalogPage.tsx` | module owner |
| `src/theme.ts` | Colors, fonts, light/dark backgrounds | Stefania (shared) |

Shared files are changed in their own small PR, so everyone notices.

## Add your page (step by step)

1. **API calls:** create `src/api/<module>.ts` with typed functions that call `apiFetch`:
   ```ts
   import { apiFetch } from './client.ts'
   import type { PageResponse } from './types.ts'

   export type AssetStatus = 'AVAILABLE' | 'UNDER_MAINTENANCE' | 'RETIRED'
   export type Asset = { id: number; name: string; model: string; status: AssetStatus }

   export function searchAssets(page: number) {
     return apiFetch<PageResponse<Asset>>(`/api/v1/assets?page=${page}&size=20`)
   }
   ```
2. **Page:** create `src/pages/<Name>Page.tsx`. Start with `<PageHeader>`, show errors with `<ErrorAlert>`, lists with `<PagedTable>`.
3. **Route:** in `src/App.tsx`, replace your `<PlaceholderPage>` with your page. Admin pages go inside the `admin` route (it already checks the role).
4. **Menu:** only if it's a new menu entry, add a line in `src/layout/navigation.ts`.
5. **Error codes:** add your module's codes to `src/api/errorMessages.ts`.
6. **PR:** screenshots in light and dark mode, `npm run lint && npm run build` green.

## Routes

| Address | Page | Who | Owner |
|---|---|---|---|
| `/login` | Sign in | everyone | Stefania |
| `/catalog` | Equipment (catalog, asset details, booking) | logged in | Liviu (catalog), Calin (booking) |
| `/reservations` | My reservations | logged in | Calin |
| `/incidents` | My incidents | logged in | Stefania |
| `/admin/reservations` | Reservations desk | admin | Matei |
| `/admin/incidents` | Incident queue | admin | Stefania |
| `/admin/reassignments` | Reassignment log | admin | Stefania |
| `/admin/inventory` | Inventory | admin | Liviu |

> [!NOTE]
> **Liviu (@NliviuN): Equipment Catalog & Booking View Integration**  
> Calin has built the reusable Google Calendar-style booking component at `src/components/CalendarBookingView.tsx`. When you build `/catalog` (`CatalogPage.tsx`), render your asset grid/filters; when an asset is selected, embed `<CalendarBookingView asset={selectedAsset} onBack={() => setSelectedAsset(null)} onSuccess={() => navigate('/reservations')} />`. Full details and props are documented in `documentation/Murariu_Calin/08-frontend-reservations-and-booking.md`.

The frontend hides admin pages from students, but the real protection is the backend: `/api/v1/admin/**` answers 403.

## Rules

| Rule | Why |
|---|---|
| Call the backend only through `apiFetch` (never `fetch` or axios directly) | One place adds the token and reads errors |
| Show errors with `<ErrorAlert error={error} />` | Same message for the same `code` everywhere |
| Imports keep the extension: `'./pages/CatalogPage.tsx'` | Vite template default, one style for the team |
| No TypeScript `enum`; use string unions: `type Status = 'OPEN' \| 'RESOLVED'` | `erasableSyntaxOnly` in `tsconfig.app.json` rejects `enum` |
| Mantine components and props for layout and spacing (`p="md"`, `c="dimmed"`), no extra CSS frameworks | Light/dark mode and the theme then work by themselves |
| Colors from the theme (`color="campus"`, `c="dimmed"`), not hex codes in pages | Changing `theme.ts` changes the whole app |
| Dates from the backend are campus local time without offset (`"2026-11-03T14:00:00"`); send them back in the same format, never `toISOString()` | api-contract §1 (`toISOString()` converts to UTC) |
| The user id never goes into a request body | The backend reads it from the token (`CurrentUser`) |
| `PagedTable` pages start at 0, like the backend | It converts for Mantine's pagination by itself |

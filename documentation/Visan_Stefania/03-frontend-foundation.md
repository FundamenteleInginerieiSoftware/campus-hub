# The React App Everyone Builds On (Vișan Ștefania)

**Role:** Member 4, frontend lead
**Date:** 2026-11-01 (ADR-002 confirmed 2026-11-06)
**Folder:** `frontend/`
**PRs:** #84 (scaffold), #85 (API client, auth, login), #86 (layout, routes, shared components), #111 (ADR-002)

---

## Part 1: In simple terms

The website is one app shared by the whole team. I built the **frame**: the menu on the left, the login, the colors and the building blocks. Each colleague then adds the pages of their own module inside it, without writing the same things again.

What the frame gives everyone:

| Piece | What it does for a page |
|---|---|
| **One way to call the server** (`apiFetch`) | adds the login ticket to every call; turns any server error into a clear message |
| **Login memory** (`AuthProvider`) | the user stays logged in after a reload; if the ticket expires, they go back to the login page |
| **Doors** (`RequireAuth`, `RequireAdmin`) | only logged-in users see the app; only admins see admin pages |
| **Menu per role** | students see Catalog, My reservations, My incidents; admins also see the desk, the incident queue, the reassignment log and the inventory |
| **Building blocks** | page title, error box, table with pages |
| **Theme** | the blue, fonts and light/dark backgrounds of our prototype |

The rules are written in `docs/modules/frontend.md`, with a 6-step "add your page" guide.

**ADR-002 (decision):** in production the Spring Boot app also serves the built website, so the browser sees one address for both. No extra setup for cross-site calls (CORS), and one deploy instead of two.

---

## Part 2: Technical details

### Stack

| Tool | Version | Why |
|---|---|---|
| Vite | 8 | fast dev server; its proxy sends `/api/...` to `localhost:8080` in dev (no CORS) |
| React | 19 | team decision 2026-10-21 |
| TypeScript | 7, `erasableSyntaxOnly` | types catch mistakes at `npm run build`; no `enum`, string unions instead |
| Mantine | 9.7 | ready components (forms, tables, AppShell), light/dark mode |
| React Router | 8 | URL per page, nested routes |
| Tabler icons | 3.50 | same icon set as the prototype |
| oxlint | 1.89 | fast linter; CI runs it |

CI (`.github/workflows/ci.yml`, frontend job): `npm ci`, `npm run lint`, `npm run build` on every PR.

### Files by PR

| PR | Files | Content |
|---|---|---|
| #84 | `package.json`, `vite.config.ts`, `tsconfig*.json`, `.oxlintrc.json`, `main.tsx`, `App.tsx`, `theme.ts`, `NotFoundPage.tsx` | scaffold, Mantine provider, router |
| #85 | `api/client.ts`, `api/auth.ts`, `api/errorMessages.ts`, `auth/AuthProvider.tsx`, `auth/useAuth.ts`, `auth/RequireAuth.tsx`, `pages/LoginPage.tsx` | talking to the backend, login state |
| #86 | `layout/AppLayout.tsx`, `layout/navigation.ts`, `auth/RequireAdmin.tsx`, `components/{PageHeader,ErrorAlert,PagedTable}.tsx`, `api/types.ts`, `pages/PlaceholderPage.tsx`, `index.html`, `docs/modules/frontend.md` | frame, menu, shared parts, conventions |

### `apiFetch<T>(path, { method, body })` (`api/client.ts`)

| Step | What |
|---|---|
| 1 | reads the token from `localStorage` (`campus.token`), adds `Authorization: Bearer ...` |
| 2 | sends JSON (`Content-Type` only when there is a body) |
| 3 | on an error status, reads the backend's ProblemDetail (`code`, `detail`, `errors[]`) into an `ApiError(status, code, message, fieldErrors)` |
| 4 | on a 401 **with** a token: forgets the token and calls the handler set by `AuthProvider` → login page. A 401 without a token (wrong password at login) is just an error |
| 5 | 204 → `undefined`; otherwise the parsed JSON as `T` |

`tokenStorage` wraps `localStorage` in `try/catch`: private mode or blocked site data throw, and the app must keep working.

`errorMessages.ts`: one friendly text per error `code`; every module adds its own codes. `ErrorAlert` shows that text plus the field list of `VALIDATION_FAILED`.

### Login state (`auth/`)

| File | Role |
|---|---|
| `useAuth.ts` | `AuthContext` + `useAuth()` hook: `{ user, loading, login, logout }` |
| `AuthProvider.tsx` | on start, if a token is saved, `GET /api/v1/users/me` restores the user; registers the 401 handler |
| `RequireAuth.tsx` | spinner while checking; no user → `/login`, remembering the page in `state.from` |
| `RequireAdmin.tsx` | not an admin → back to `/` (the start page); the real protection is the backend's 403 on `/api/v1/admin/**` |

Token storage choice: `localStorage` (survives a browser restart; the token expires after 8 h). The alternative, an `HttpOnly` cookie, would need CSRF protection on the backend.

### Layout and routes

- `AppLayout`: Mantine `AppShell`, menu from `navigation.ts` by role, user name + role at the bottom, light/dark switch, logout.
- `App.tsx`: `/login` public; everything else under `<RequireAuth><AppLayout/></RequireAuth>`; admin pages under the `admin` route with `<RequireAdmin/>`. Each owner replaces their `<PlaceholderPage owner="...">` with the real page.

### Shared components

| Component | Props | Used for |
|---|---|---|
| `PageHeader` | `title`, `description`, optional actions | the top of every page |
| `ErrorAlert` | `error: unknown` | any error from `apiFetch` |
| `PagedTable<T>` | `page: PageResponse<T>`, `columns`, `rowKey`, `onPageChange`, `loading`, `emptyText` | every list; converts the backend's 0-based page to Mantine's 1-based pagination |

### Theme (`theme.ts`)

`campus` color with 10 shades, `#1f5fd0` at index 6 (Mantine's main shade); fonts Google Sans; page and surface colors for light and dark mode from the prototype through a `CSSVariablesResolver`. Pages use theme colors only, never hard-coded hex.

### ADR-002 in `docs/decisions.md`

| | Dev | Prod |
|---|---|---|
| Frontend | Vite on `:5173` | built into `frontend/dist`, copied into the Spring Boot image |
| API calls | Vite proxy → `:8080` | same origin behind CloudFront |
| CORS | none | none |

Matei's Dockerfile runs `npm ci && npm run build`. A later Spring fallback sends non-`/api` paths (like `/reservations/42`) to `index.html`.

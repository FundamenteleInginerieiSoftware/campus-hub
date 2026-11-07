# Register Page and Login Robustness (Vișan Ștefania)

**Role:** Member 4, frontend lead
**Date:** 2026-11-06
**PRs:** #107 (register page), #142 (keep the token when the backend is down, closes #124), #145 (unique error keys + `/admin` start page, closes #125)

---

## Part 1: In simple terms

**Register.** A new student opens the app, clicks "Create an account", types their name, university email and a password, and is logged in straight away. New accounts are always students; admins are only added by an admin. The password is checked in the browser first (at least 8 characters, not too long), so the student gets the message without waiting for the server; the server checks again anyway.

**Restart-proof login.** Before, if you reloaded the page while the server was restarting (every deploy takes about 30 seconds), the app thought your login was invalid and logged you out. Now it only logs you out when the server really says "this ticket is invalid". Otherwise it shows "Cannot reach the server" with a **Try again** button that logs you straight back in.

**Two small bugs found by Matei.** The error box could show two messages for the same field and React complained about it; and an admin opening `/admin` saw an empty page. Now both messages show correctly and `/admin` opens the reservations desk.

---

## Part 2: Technical details

### Register (#107)

| File | Change |
|---|---|
| `components/AuthLayout.tsx` (new) | the prototype's split screen (form left, blue panel right, hidden on phones), shared by login and register |
| `pages/RegisterPage.tsx` (new) | form: full name (max 100), email (max 255), password; `register()` then `login()` with the same credentials, then `navigate('/')` |
| `pages/LoginPage.tsx` | uses `AuthLayout`; link "Create an account"; dev-only quick-fill buttons (`import.meta.env.DEV`) |
| `App.tsx` | public route `/register` |

Client-side password check (`passwordProblem`):

| Rule | Why |
|---|---|
| `length >= 8` | backend rule (api-contract §3) |
| `new TextEncoder().encode(password).length <= 72` | BCrypt reads at most 72 **bytes**; letters like `ș` take 2 bytes in UTF-8, so the character count is not enough |

Server errors (`EMAIL_TAKEN` 409, `VALIDATION_FAILED` 400 with a field list) go through `ErrorAlert`. Email trimming and case are handled by the backend (Matei, #175).

### Keep the token when the backend is down (#142, closes #124)

| Before | After |
|---|---|
| `fetchMe().catch(() => tokenStorage.clear())`: any error deleted a still-valid token | only an `ApiError` with status **401** forgets it (and `apiFetch` already does that) |
| 502 during a deploy → login page | `startupError` is set → `RequireAuth` shows "Cannot reach the server" with **Try again** and **Log out** |

| File | Change |
|---|---|
| `auth/useAuth.ts` | `AuthState` gets `startupError: unknown` and `retry()` |
| `auth/AuthProvider.tsx` | `checkSavedToken()` used on start and by `retry()`; `logout` and `login` clear `startupError` |
| `auth/RequireAuth.tsx` | the error screen (Mantine `Alert` + two buttons) between the spinner and the redirect |

Tested by hand against a mock backend answering 502 and then 200: error screen, the token still in `localStorage`, **Try again** logs back in.

### Unique keys and `/admin` (#145, closes #125)

| Bug | Cause | Fix |
|---|---|---|
| "two children with the same key" | the backend sends one entry per broken rule, so `password` can appear twice; `key={fieldError.field}` | `key={`${fieldError.field}-${index}`}` in `ErrorAlert` |
| empty page at `/admin` | the `admin` route had no index child | `<Route index element={<Navigate to="reservations" replace />} />` |

### Checks

`npm run lint` and `npm run build` (TypeScript) on every PR; screenshots attached to the frontend PRs.

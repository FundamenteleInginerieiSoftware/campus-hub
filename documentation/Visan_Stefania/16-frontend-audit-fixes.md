# Frontend Audit Fixes (Vișan Ștefania)

**Role:** Member 4 (frontend lead)
**Date:** 2026-11-27
**Files:** `frontend/src/pages/IncidentDetailsPage.tsx`, `AdminUsersPage.tsx`, `AdminReliabilityPage.tsx`, `RegisterPage.tsx`, `LoginPage.tsx`, `frontend/src/api/client.ts`, `errorMessages.ts`, `docs/modules/frontend.md`, `docs/whats-left.md`, `AGENTS.md`, `documentation/Visan_Stefania/README.md`
**PR:** #369 (Refs #364)

---

## Part 1: In simple terms

The #364 audit found a few places where the app showed the wrong thing or got stuck. This PR fixes the ones in my pages and in the shared API client:

1. **Incident details:** going straight from one incident to another kept the old one on screen, and a "not found" for the new one was never shown. Now the page starts fresh for every incident.
2. **Users:** typing a letter in the search box and deleting it quickly left the table loading forever. Now it never gets stuck.
3. **Equipment health:** an error from the previous period stayed on screen while the new period loaded. Now it is cleared.
4. **Shared API client:** `apiFetch` can now send extra headers for one request. Calin's booking call needs this (`Idempotency-Key`) to stop using its own copy of the client, which did not send the student to the login page when the login expired. The switch itself is in his file, so he does it in his #362 PR.
5. **Error texts:** 13 error codes the backend can send had no friendly text. Added.
6. **Register:** if the account was created but the automatic login after it failed, the error showed on the register form, and trying again gave "email already taken". Now the student lands on the login page, with the email filled in and an "account created" notice.

---

## Part 2: Technical details

### 1. `IncidentDetailsPage`: a key per id

React Router keeps the same component when only `:id` changes, so every `useState` (incident, photo, error) kept its old value. The error is only rendered when no incident is loaded, so a 404 for the new id stayed hidden behind the old incident.

```tsx
export default function IncidentDetailsPage() {
  const { id } = useParams()
  return <IncidentDetails key={id ?? ''} id={id} />
}
```

A new `key` makes React unmount the old tree and mount a new one, so all state, the photo included, starts from scratch. This is React's documented way to reset state when an input changes; resetting each `useState` by hand in an effect is easy to miss for one of them.

### 2. `AdminUsersPage`: loading is computed, not stored

Before: `setLoading(true)` on every filter change, `setLoading(false)` when the search came back. The search text is debounced (300 ms): type "a" and delete it within 300 ms and `debouncedQ` never changes, so no search runs and `loading` stays `true`.

Now:

```tsx
const requestKey = `${debouncedQ}|${role}|${status}|${pageNumber}|${reloadToken}`
const [loadedKey, setLoadedKey] = useState<string | null>(null)
const loading = q !== debouncedQ || loadedKey !== requestKey
```

The effect stores `requestKey` in `loadedKey` when its search ends (success or error). The table waits while the text is still settling or while the data on screen belongs to other filters. No `setLoading` call is left to forget.

### 3. `AdminReliabilityPage`

`setError(null)` before loading the new period.

### 4. `apiFetch` extra headers

`RequestOptions` gets `headers?: HeadersInit`. Inside, the headers are built with `new Headers(options.headers)` and then `apiFetch` decides the three that matter:

```ts
const headers = new Headers(options.headers)
headers.set('Accept', 'application/json')
if (isForm) headers.delete('Content-Type')          // the browser adds multipart + boundary
else if (options.body !== undefined) headers.set('Content-Type', 'application/json')
if (token) headers.set('Authorization', `Bearer ${token}`)
else headers.delete('Authorization')
```

Why `Headers` and not `{ Accept: ..., ...options.headers }` (Matei's review): a plain object is case-sensitive, so a caller's `accept` or `authorization` would sit next to ours with other casing, could override `Accept`, could keep a `Content-Type` on a file upload (which breaks the multipart boundary), or send a caller's `Authorization` when nobody is logged in. `Headers` names are case-insensitive and `set`/`delete` always win.

`createReservation` (`src/api/reservations.ts`) is Calin's file, so it is **not** changed here: he switches it to `apiFetch(..., { headers: { 'Idempotency-Key': key } })` in his #362 PR, keeping his key rules (a 400 or 401 drops the key, anything else keeps it). That also fixes the 401 gap (his copy clears the token but never calls `onUnauthorized`).

### 5. `errorMessages.ts`

`USER_DISABLED`, `BUNDLE_NOT_FOUND`, `CONFLICTING_PARAMETERS`, `CALENDAR_FEED_NOT_FOUND`, `PROJECT_NOT_FOUND`, `PICKUP_CODE_INVALID`, `LIVE_STREAM_LIMIT`, plus the six from #364 (`HOLD_COOLDOWN_ACTIVE`, `TRANSFER_LIMIT_REACHED`, `BUNDLE_EXTEND_NOT_ALLOWED`, `BUNDLE_TRANSFER_NOT_ALLOWED`, `INVALID_BUNDLE_SIZE`, `CONCURRENT_IDEMPOTENT_REQUEST`). Found by comparing the codes thrown in `backend/src/main` with the keys of `MESSAGES`; a one-line grep misses a code when Spotless puts it on the line after `new ...Exception(`, so the search has to look at the next line too. Generic fallbacks (`CONFLICT`, `BUSINESS_RULE_VIOLATION`) keep the backend's own text.

### 6. Register → Login when the auto-login fails

`register` and `login` are now in separate `try` blocks. A failed `register` shows its error on the form (as before). A failed `login` after a successful `register` navigates to `/login` with `state: { registeredEmail }`; `LoginPage` prefills the email and shows a green "Your account was created" alert.

### Not in this PR

Section 3 of #364 (optional backend items) is left for a separate PR.

### How to test

1. `cd backend && ./gradlew bootRun`, then `cd frontend && npm run dev`.
2. Admin → Users: type a letter and delete it quickly → the list shows, no endless loader.
3. Student: report two incidents, open one, go back, open the other → the right incident and photo each time.
4. Admin → Equipment health: switch the period → no stale error.
5. Book a slot and upload an incident photo → both still work (JSON and file upload headers).

### Checks

`npm run lint` (oxlint) clean, `npx tsc -b` and `npm run build` OK. No frontend test setup, so checked by hand (screenshots in the PR).

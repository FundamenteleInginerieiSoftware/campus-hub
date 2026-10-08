# Member 4: Incident Hub, Auto-Reassignment Engine & Frontend Lead

> Read `00-TEAM-HANDBOOK.md` first. This file only covers what is specific to you.
> You own the **standout feature** (the non-CRUD auto-reassignment engine) and the **face of the project** (the UI the professor clicks through). You also integrate everyone's work. This is the **largest role**, so the plan below protects your time on purpose (§6).

---

## 1. Project in one paragraph

Campus Equipment & Incident Hub: students book lab equipment by time slot (overlaps rejected) and report broken equipment with photos. Damaged assets are locked automatically. An **auto-reassignment engine** moves future bookings of a broken asset to a substitute, or force-cancels them, and notifies the students. Spring Boot 4.1 / Java 21 backend (modular monolith), React + Vite + TypeScript frontend, H2 locally and PostgreSQL on AWS (deployed by Matei).

## 2. What you own

**Backend packages:** `com.campus.incident`, `com.campus.reassignment`. **Frontend:** `frontend/` (you own `package.json`, structure and conventions).

| Item | Details |
|---|---|
| Incident entity | `IncidentReport` (assetId, reportedById, reservationId?, description, severity, photoKey, status, createdAt, resolvedAt, resolvedById, resolutionNote) |
| Incident enums | `IncidentStatus { OPEN, IN_REPAIR, RESOLVED, REJECTED }`, `Severity { LOW, MEDIUM, HIGH }` |
| Incident flows | Report (with optional photo) → asset to maintenance → publish `AssetDamagedEvent`; admin triage: start repair / resolve / reject → asset back to `AVAILABLE` when no open incidents remain |
| Event | `incident.api.AssetDamagedEvent` record (JSON-serializable, also travels through SQS in prod) |
| Reassignment engine | Local event listener (non-prod), `ReassignmentService.handle(event)`, **Strategy pattern** substitute search, `reassignment_log` table, notifications, admin log endpoint |
| Frontend | All pages, routing, auth handling, API client, UI kit, build for S3/CloudFront |
| Seed data | `@Profile("dev") @Order(4) IncidentSeeder` (1–2 historical incidents) |
| Docs (lead) | **Use case diagram**, Incident state diagram, sequence diagram "incident → reassignment", **Strategy & Observer pattern documentation**, **user manual with screenshots**, **demo script** |

---

## 3. How your work connects to others

| Who | Relationship |
|---|---|
| **Member 2 (Catalog)** | You call `catalogApi.getAsset`, `markUnderMaintenance`, `markAvailable`, `findAvailableByModel`, `findAvailableByCategory`. Make sure `assets.model` exists (entity freeze) and that the seed data has **several units per model and category**, otherwise the demo force-cancels everything. |
| **Member 3 (Reservations)** | You call `findUpcomingActiveByAsset`, `isAssetFree`, `moveToAsset`, `forceCancel`, `findCurrentCheckout`. Agree on what `moveToAsset` throws when the substitute was taken in the meantime (you then try the next candidate). |
| **Matei** | Gives you `StorageService` (photos), `NotificationService`, `AuditService`, `CurrentUser`, async config, the JWT/login contract for the frontend, frontend hosting. **In prod, Matei's `messaging` module forwards your event to SQS and calls your `ReassignmentService.handle()` from the consumer.** Your local listener must be `@Profile("!prod")` so it never runs twice. |
| **Everyone (frontend)** | You consume all REST endpoints. Ask each backend owner to (a) keep `docs/api-contract.md` with JSON examples current and (b) write the TS types + API functions for their module in `frontend/src/api/<module>.ts` (you review those PRs). |

---

## 4. Backend API (draft: confirm in `docs/api-contract.md` in week 2)

| Method & path | Who | Description |
|---|---|---|
| `POST /api/v1/incidents` (multipart form) | logged in | Fields: `assetId`, `description`, `severity`, optional `photo` → 201 |
| `GET /api/v1/incidents/me?page=` | logged in | My reports |
| `GET /api/v1/incidents/{id}` | reporter/admin | Details |
| `GET /api/v1/incidents/{id}/photo` | reporter/admin | Streams the image bytes (works the same for local disk and S3) |
| `GET /api/v1/admin/incidents?status=&assetId=&page=` | admin | Triage queue (`OPEN` first, `HIGH` severity first) |
| `POST /api/v1/admin/incidents/{id}/start-repair` | admin | `OPEN` → `IN_REPAIR` |
| `POST /api/v1/admin/incidents/{id}/resolve` | admin | → `RESOLVED` (+ note); asset → `AVAILABLE` if no other `OPEN`/`IN_REPAIR` incidents on it |
| `POST /api/v1/admin/incidents/{id}/reject` | admin | → `REJECTED` (false report); same asset rule |
| `GET /api/v1/admin/reassignments?incidentId=&page=` | admin | Reassignment log (what moved where, what was cancelled, which strategy) |

Error codes you own: `INCIDENT_NOT_FOUND`, `ASSET_RETIRED`, `INVALID_PHOTO_TYPE`, `PHOTO_TOO_LARGE`, `INVALID_INCIDENT_STATUS_TRANSITION`.

---

## 5. Step-by-step guide

### Week 1: Learn and set up (the most to learn: Spring **and** React)
1. Install JDK 21, IntelliJ, Git, **Node 24 LTS** (via `nvm`), VS Code (optional, for the frontend). Set your Git name and **GitHub email**.
2. Clone, run the backend, open Swagger. Do the spring.io guides (handbook §14).
3. React official tutorial (react.dev "Learn") + Vite "Getting started" + a TypeScript basics overview (types, interfaces, generics at a basic level).
4. Practice PR (name in README).

### Week 2: Use cases, wireframes, decisions
1. **Lead the use case diagram** (actors: Student, Admin, System/Scheduler; use cases: register, log in, browse catalog, book, cancel, view my reservations, report incident, check out, return, triage incident, view reassignment log, view audit log, auto-reassign (system), mark overdue (system)).
2. User stories for incidents and reassignment in `docs/requirements/incident.md`. Example:
   > *As a student with a booking on a kit that just broke, I want to be moved automatically to an identical working kit so that my lab session isn't lost.*
   > Given asset A (model X) is booked by me tomorrow 10–12 and asset B (model X) is free then, when someone reports A as broken, then my booking is on B, `reassignment_log` has an entry `REASSIGNED / SameModelStrategy`, and I receive a notification.
3. **Wireframes** (paper/Excalidraw/Figma) for every page in §5.8. Show them to the team. Everyone then knows which data each screen needs, which drives the API contract.
4. **Pick the UI kit once** (recommended: **Mantine**, which has tables, forms, notifications **and** date/time pickers built in, so you save weeks).
5. Agree the **date-time JSON format** with Member 3 (see §7) and the login response format with Matei.

### Week 3: Entity freeze + frontend scaffold
1. Migration, e.g. `V2026_10_20_1200__incident_create_tables.sql`:
   ```sql
   CREATE TABLE incident_reports (
     id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
     asset_id BIGINT NOT NULL REFERENCES assets(id),
     reported_by_id BIGINT NOT NULL REFERENCES users(id),
     reservation_id BIGINT REFERENCES reservations(id),
     description VARCHAR(2000) NOT NULL,
     severity VARCHAR(16) NOT NULL,
     photo_key VARCHAR(255),
     status VARCHAR(32) NOT NULL,
     created_at TIMESTAMP NOT NULL,
     resolved_at TIMESTAMP,
     resolved_by_id BIGINT REFERENCES users(id),
     resolution_note VARCHAR(1000),
     version BIGINT NOT NULL DEFAULT 0
   );
   CREATE INDEX idx_incidents_asset_status ON incident_reports(asset_id, status);
   CREATE TABLE reassignment_log (
     id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
     incident_id BIGINT NOT NULL REFERENCES incident_reports(id),
     reservation_id BIGINT NOT NULL REFERENCES reservations(id),
     from_asset_id BIGINT NOT NULL REFERENCES assets(id),
     to_asset_id BIGINT REFERENCES assets(id),
     outcome VARCHAR(32) NOT NULL,
     strategy VARCHAR(64),
     created_at TIMESTAMP NOT NULL
   );
   ```
   It references `reservations`, so its timestamp must be **after** Member 3's migration.
2. Entity with plain IDs for cross-module references; transitions inside the entity (`startRepair()`, `resolve(by, note, now)`, `reject(by, note, now)`).
3. Frontend scaffold: `npm create vite@latest frontend -- --template react-ts`, add the packages (handbook §4.2), `.nvmrc` with `24`, ESLint + Prettier, and the folder structure from handbook §5.
4. `vite.config.ts` dev proxy (no CORS needed, no hardcoded backend URL):
   ```ts
   export default defineConfig({
     plugins: [react()],
     server: { proxy: { '/api': 'http://localhost:8080' } },
   });
   ```
5. `src/api/client.ts`: one axios instance (`baseURL: '/api/v1'`); a request interceptor adds `Authorization: Bearer <token>`; a response interceptor on **401** clears the token and redirects to `/login`; a helper that turns `ProblemDetail` (`code`, `detail`) into a user-friendly message.
6. Auth: `AuthContext` (token + user from the login response), `ProtectedRoute`, `AdminRoute`, Login and Register pages. Store the token in `localStorage` (simple; mention the XSS trade-off in the report; an httpOnly cookie would be the production-grade alternative).
7. Matei's CI should run `npm ci && npm run build` (+ lint). Make sure the build passes.

### Week 4: Report incident (backend) + catalog page (frontend)
1. `POST /api/v1/incidents`. Use **`@ModelAttribute` form fields + `MultipartFile photo`** rather than `@RequestPart` with a JSON part:
   ```java
   @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
   public ResponseEntity<IncidentResponse> report(@Valid @ModelAttribute ReportIncidentForm form) { … }
   // record ReportIncidentForm(@NotNull Long assetId, @NotBlank @Size(max=2000) String description,
   //                           @NotNull Severity severity, MultipartFile photo) {}
   ```
   (With a JSON `@RequestPart`, the browser must send that part as a `Blob` with type `application/json`, otherwise you get 415 errors. Form fields avoid this trap.)
2. `IncidentService.report()` in **one `@Transactional` method**:
   1. `catalogApi.getAsset(assetId)`; if `RETIRED` → 409.
   2. If a photo is present: validate the type (`image/jpeg`, `image/png`, `image/webp`) and size (≤5 MB), then `storageService.store(...)` → key. (Matei's storage generates safe names; never use the original filename as a path.)
   3. Optional: `reservationApi.findCurrentCheckout(assetId)` → link `reservationId`.
   4. Save the incident (`OPEN`) and `catalogApi.markUnderMaintenance(assetId)`.
   5. `eventPublisher.publishEvent(new AssetDamagedEvent(incident.getId(), assetId, CurrentUser.id(), clock.instant()))`.
   6. Audit; return 201.
   - If the DB transaction fails after the file was stored, you get an orphan file. Acceptable for this project, but delete it in a `catch` if easy, and mention it.
3. Frontend: **catalog page** (list + filters + paging) and asset details. Work with Member 2.

### Week 5: Photos, triage, booking UI
1. `GET /incidents/{id}/photo`: ownership check (reporter or admin, else 404) → `storageService.load(key)` → stream with the correct `Content-Type`.
   - **`<img src="/api/v1/incidents/5/photo">` will NOT work.** The browser doesn't send your Bearer token for `<img>`. Fetch it with axios (`responseType: 'blob'`), then `URL.createObjectURL(blob)` (and revoke it on unmount).
2. Admin triage endpoints + rule: when resolving/rejecting, call `catalogApi.markAvailable(assetId)` **only if** there is no other `OPEN`/`IN_REPAIR` incident for that asset.
3. Frontend: **booking UI** with Member 3: date + time pickers aligned to 30 min, a busy-interval display from the availability endpoint, submit, and a friendly error on 409 `RESERVATION_CONFLICT`.
4. Frontend: **report incident** form (asset select, description, severity, photo input with preview and client-side size/type check).

### Week 6: M1 integration
1. Pages for M1 working end-to-end: login/register, catalog, book, my reservations, admin inventory (with Member 2), admin reservations checkout/return (with Member 3).
2. Run the full M1 flow with the team on `main`. Tag `v0.1.0` (Matei).
3. **Workload retro:** if the frontend is behind, split the remaining pages now (§6).

### Week 7: Reassignment engine v1 (your highlight)
1. **Listener (non-prod):**
   ```java
   @Component
   @Profile("!prod")
   @RequiredArgsConstructor
   class LocalAssetDamagedListener {
       private final ReassignmentService reassignmentService;
       @Async
       @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
       public void on(AssetDamagedEvent event) { reassignmentService.handle(event); }
   }
   ```
   - `AFTER_COMMIT`: runs only if the incident was really saved, and doesn't roll the incident back if reassignment fails.
   - `handle()` is `@Transactional` on **another bean** (`ReassignmentService`), so it gets its own transaction (after commit there is no active transaction to join).
2. **Strategy pattern:**
   ```java
   public interface SubstituteStrategy {
       String name();
       Optional<AssetSummary> findSubstitute(ReservationSummary reservation, AssetSummary damaged);
   }
   @Component @Order(1) class SameModelStrategy implements SubstituteStrategy { … }    // catalogApi.findAvailableByModel
   @Component @Order(2) class SameCategoryStrategy implements SubstituteStrategy { … } // catalogApi.findAvailableByCategory
   // Spring injects List<SubstituteStrategy> sorted by @Order
   ```
   Each strategy: get the candidates (prefer the same lab room), keep the first one where `reservationApi.isAssetFree(candidate, r.start, r.end, r.id)`.
3. **Algorithm (`ReassignmentService.handle`):**
   ```
   damaged  = catalogApi.getAsset(event.assetId)
   bookings = reservationApi.findUpcomingActiveByAsset(event.assetId, now(clock))   // sorted by start
   for each booking:
       for each strategy in order:
           candidate = strategy.findSubstitute(booking, damaged)
           if candidate present:
               try reservationApi.moveToAsset(booking.id, candidate.id, "Incident #" + incidentId)
                   log REASSIGNED (from, to, strategy); notify student "moved to <asset>"; next booking
               catch SubstituteNoLongerFree → try next candidate/strategy
       if nothing worked:
           reservationApi.forceCancel(booking.id, "Equipment failure, incident #" + incidentId)
           log FORCE_CANCELLED; notify student "cancelled, sorry"
   ```
   - **Process bookings in start-time order** and persist each move immediately. Then booking #2 sees that booking #1 already took substitute B for 10–12.
   - **Idempotency:** a second run for the same event finds no `CONFIRMED` bookings left on the damaged asset and does nothing. Write a test for this (SQS may deliver twice in prod).
   - **Notifications after the work**, and a failed notification must not undo a reassignment (catch + log).
   - Optional: process each booking in its own transaction (a separate bean method with `REQUIRES_NEW`), so one failure doesn't roll back the others.
4. **Tests** (unit, Mockito mocks of `CatalogApi` and `ReservationApi`):
   - same-model substitute free → moved, logged with `SameModelStrategy`
   - no same model, same category free → moved with `SameCategoryStrategy`
   - nothing free → force-cancelled
   - two bookings competing for one substitute in the same slot → the first is moved, the second cancelled (or moved elsewhere)
   - substitute taken concurrently (`moveToAsset` throws) → next candidate tried
   - second run of the same event → no changes
   - damaged asset with no future bookings → nothing happens
5. **Integration test** (`@SpringBootTest`): report an incident through the API → the reservation is moved. `@Async` makes tests flaky, so either use Awaitility (`await().atMost(5, SECONDS).until(...)`) or configure a synchronous executor in the test profile (ask Matei).

### Week 8: Engine v2 + admin views
1. `GET /api/v1/admin/reassignments` + frontend page (table: incident, student, from → to, outcome, strategy, time).
2. Admin **incident queue** page: filter by status, severity badge, photo viewer, start-repair/resolve/reject buttons with a note.
3. "My reservations" shows moved bookings clearly ("Moved to Rigol #4 due to equipment failure") and force-cancelled ones.
4. With Matei: confirm `AssetDamagedEvent` serializes to JSON cleanly (only simple fields: `Long`, `Instant`) for SQS, and that prod calls the same `handle()`.

### Week 9: M2: feature complete
1. Whole flow locally **and** on AWS: student books → another student reports the asset broken (with photo) → booking moved/cancelled → notification → admin repairs → asset available again.
2. Frontend polish pass: loading states, empty states, error toasts using the `code` from ProblemDetail, confirmation dialogs for destructive actions, a responsive layout (the demo might happen on a phone: "report incident from the lab").
3. Build check for prod: `npm run build` → `dist/`; give Matei the command and output folder.

### Weeks 10–12: Tests, docs, demo (you lead the demo)
1. Tests ≥70% on `incident.service` and `reassignment`. Frontend tests are optional (a few Vitest + Testing Library tests on the API client / a form are a nice bonus).
2. A **manual end-to-end test checklist** for the whole app, run before each milestone (also good test-report material for Member 3).
3. Docs: use case diagram (final), incident state diagram, **sequence diagram "incident → reassignment"**, **Strategy + Observer documentation** (class diagram of `SubstituteStrategy` + implementations; event flow dev vs. prod), **user manual with screenshots**.
4. **Demo script** (10 min) with seed data prepared so the story works every time:
   1. Login as student1 → browse → book "Rigol #1" tomorrow 10–12 → try an overlapping booking as student2 → 409 shown nicely.
   2. Student2 reports "Rigol #1" broken with a photo.
   3. Student1's booking is **automatically moved to Rigol #3**; show the notification/log.
   4. A booking on a model with no free substitute → force-cancelled.
   5. Admin: incident queue → photo → resolve → asset back to available; reassignment log; audit log.
   6. (Matei) Show the same on AWS + the architecture diagram.
   Rehearse **twice**, keep a **local fallback** and a **recorded video**.

### 5.8 Frontend pages (priority order: build top-down, stop when time runs out)

| # | Page | Role | Backend owner |
|---|---|---|---|
| 1 | Login / Register | all | Matei |
| 2 | Catalog (search, filters, paging) + Asset details | student | Member 2 |
| 3 | Book a slot (on asset details: pickers + busy intervals) | student | Member 3 |
| 4 | My reservations (cancel; moved/cancelled badges) | student | Member 3 |
| 5 | Report incident (from asset details or a reservation) | student | you |
| 6 | Admin: reservations desk (today's pickups/returns, checkout/return buttons) | admin | Member 3 |
| 7 | Admin: incident queue + details + photo + actions | admin | you |
| 8 | Admin: inventory (assets/categories/rooms CRUD) | admin | Member 2 |
| 9 | Admin: reassignment log | admin | you |
| 10 | My incidents | student | you |
| 11 | Admin: audit log | admin | Matei |

---

## 6. Workload protection (read this, and tell the team if you're overloaded)

You have 2 backend modules **plus** the entire frontend. To keep this fair:
- **Backend owners write their own API layer for the frontend:** `src/api/<module>.ts` + `src/types/<module>.ts`. You review.
- **After M1 (W6), at the retro:** if the frontend is behind, hand over full pages. Suggested: Member 2 builds page 8 (inventory admin), Member 3 builds page 6 (reservations desk), Matei builds page 11 (audit log), all following your components and conventions. You keep the student-facing pages, the incident pages and the overall look.
- **Use Mantine components as they are.** Don't build custom design systems, animations or a dark mode before M2.
- **Vertical slices:** finish "backend endpoint + its page" together rather than all backend first, so integration problems show up early.
- **Your priority order when time is short:** (1) incident report + reassignment engine + its tests; (2) student pages 1–5; (3) admin pages 6–7; (4) the rest.

---

## 7. Things to keep in mind

- **Dates:** never send `date.toISOString()` (UTC with `Z`, shifts bookings by 2–3h). Send local campus time without an offset: `dayjs(d).format('YYYY-MM-DDTHH:mm:ss')`. Display what the backend returns as-is (it's already campus time).
- **No hardcoded URLs:** relative `/api/...` only. The Vite proxy handles dev, CloudFront handles prod.
- **Hiding a button is not security.** The backend enforces roles and ownership; the UI only hides things for convenience.
- **Handle 401 globally** (token expired after 8h → back to login with a message), **403** ("you don't have access"), **409** (show the `detail`/mapped `code` message), **400** (show field errors next to the inputs).
- **Types mirror DTOs.** When a backend DTO changes, the TS type changes in the same PR (or right after). Mismatches show up as `undefined` on screen, not as errors.
- **React Query** (if used): invalidate the right queries after mutations (e.g. after booking, invalidate "my reservations" and "availability"), otherwise the UI shows stale data.
- **Event + transaction rules:** `@TransactionalEventListener(AFTER_COMMIT)`, `@Async`, handler `@Transactional` on another bean, `@Profile("!prod")` on the local listener. Getting any one wrong produces confusing bugs (handbook §11.1).
- **Photos:** don't store image bytes in the database. Store only the `photo_key`. Uploads are limited to 5 MB on the server (Matei's config), so also check on the client for a nicer message. Phone photos are often 3–8 MB, so consider client-side resizing (canvas) as a nice-to-have.
- **Privacy:** the availability endpoint and the UI never show other students' names to students.

---

## 8. Foreseeable problems (yours)

| Problem | Symptom | Fix / prevention |
|---|---|---|
| Overloaded by W6 | Pages missing, engine not started | §6: hand over pages; vertical slices; priority order |
| CORS errors in the browser | `blocked by CORS policy` | Use the Vite proxy + relative URLs. Don't ask Matei to open CORS |
| 415 on incident upload | `Content type 'application/octet-stream' not supported` | `@ModelAttribute` form fields; send `FormData` and **don't** set `Content-Type` manually (the browser adds the boundary) |
| 413 / upload rejected | `MaxUploadSizeExceededException` | Client-side size check; Matei maps it to a friendly 400/413 ProblemDetail |
| Photos don't display | Broken image icon / 401 | Fetch as a blob with the token (`<img>` doesn't send headers) |
| Reassignment never runs | Booking stays on the broken asset | Listener not a bean / wrong profile / `@EnableAsync` missing / plain `@EventListener` running before commit |
| Reassignment runs twice | Double notifications | Local listener not excluded in prod (`@Profile("!prod")`) |
| Incident rolled back because reassignment failed | Report "disappears" | `AFTER_COMMIT` listener |
| `TransactionRequiredException` / changes not saved in the handler | Moves don't persist | `@Transactional` handler on a separate bean (no self-invocation) |
| Two bookings moved onto the same substitute slot | Double booking created by **your** engine | Sequential processing + Member 3's `moveToAsset` locks and re-checks |
| Everything force-cancelled in the demo | Boring demo | Seed duplicates per model (Member 2) and plan the demo data |
| Asset stays in maintenance forever | Can't be booked after repair | Resolve/reject → `markAvailable` when no open incidents remain; test it |
| Flaky async tests | Pass/fail randomly | Awaitility or a synchronous executor in tests |
| Booking times shifted | Off by 2–3h | Date format rule (§7) |
| Stale UI after actions | List doesn't update | React Query invalidation / refetch after mutation |
| Page refresh 404 on AWS | Deep links broken | Matei's CloudFront Function rewrite to `/index.html` |
| Token expired mid-demo | Sudden redirects | 8h token; log in fresh before the demo |
| Frontend build fails in CI only | `npm ci` errors / TS errors | Commit `package-lock.json`; run `npm run build` locally before pushing; same Node version (`.nvmrc`) |
| `node_modules` committed | Huge PR | `.gitignore`; check `git status` before committing |
| Can't explain the engine at defense | Weak grade | Own the algorithm: draw the sequence diagram yourself and know every test case |

---

## 9. Personal checklist

- [ ] W1: tools + Spring + React/TS tutorials + practice PR
- [ ] W2: use case diagram; user stories; wireframes; UI kit chosen; date & login contracts agreed
- [ ] W3: incident + reassignment_log migrations + entity (freeze); frontend scaffold, proxy, API client, auth pages
- [ ] W4: report-incident endpoint + event publish; catalog page
- [ ] W5: photo upload/view; triage endpoints; booking UI; report form
- [ ] W6: M1 pages integrated; workload retro
- [ ] W7: reassignment engine (listener, strategies, algorithm) + unit tests
- [ ] W8: reassignment log + admin incident queue + "moved" UI; SQS compatibility with Matei
- [ ] W9: full flow local + AWS; UI polish; prod build
- [ ] W10–12: tests; manual E2E checklist; diagrams + pattern docs + user manual; demo script + 2 rehearsals + video backup

## 10. What you present at the defense
The use case diagram, the incident lifecycle, **the auto-reassignment engine** (Observer: events and the AFTER_COMMIT decoupling, local vs. SQS; Strategy: pluggable substitute search; idempotency; its test cases), and the UI walkthrough as the live demo.

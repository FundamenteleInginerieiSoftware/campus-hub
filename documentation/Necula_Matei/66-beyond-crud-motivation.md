# 66 — "Why This Isn't a Simple CRUD App" motivation, lock-ordering sketch, and live demos

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-26
**See also:** `README.md`, `PROGRESS.md`, `docs/diagrams/README.md`, `.github/workflows/sync-wiki.yml`, `documentation/Necula_Matei/64-wiki-sync-and-readme-nav.md`, `documentation/Necula_Matei/65-progress-engineering-stats.md`

---

## Part 1: In simple terms

1. **Why we put "Why This Isn't a Simple CRUD App" right at the top of `README.md` and `PROGRESS.md`:** on paper, *"students reserve lab equipment and report broken items"* sounds like a simple database form an LLM could generate in one prompt. In reality, basic CRUD is only the surface. The hard engineering lives in what breaks when multiple students, admins, background timers, open browser tabs, and cloud timeouts collide on a real PostgreSQL 18 database. Adding a concise case and a 60-second proof command right at the top of `README.md` — plus the full technical case at the top of `PROGRESS.md` (which auto-syncs to the GitHub Wiki `Home.md`) — makes that depth immediately visible to anyone inspecting the repository.
2. **What the two new SVG sketches show:**
   - **One-Shot CRUD vs. Campus Hub (`progress-beyond-crud.svg`):** a 6-row side-by-side comparison contrasting fragile prompt defaults (`if (!existsOverlap()) save()`, single-table `BROKEN` flips, 8-hour stale JWTs, flat MVC with `LocalDateTime.now()`, H2 happy-path mocks, `"works on localhost"`) with what Campus Hub implements and proves across locking, event-driven auto-reassignment & dry-run simulation, zero-delay kill-switch / rotating QR pass / live SSE, machine-enforced modular monolith (`28` `@ArchTest` rules), 5-layer testing (`1,221` tests, `2.16×` test-to-prod ratio, PIT mutation testing), and Terraform + OIDC AWS delivery.
   - **PostgreSQL 18 Lock-Ordering Hierarchy & Real Bugs Caught (`progress-lock-hierarchy.svg`):** maps our deadlock-free acquisition order across **Band 0** (`Idempotency-Key` in isolated `REQUIRES_NEW` pre/post transactions) and **Bands A–D** (new booking/bundle/hold, existing booking/transfer, incident report/triage, and admin kill-switch cascade) spanning `10` `SELECT ... FOR UPDATE` queries, `2` non-colliding advisory lock namespaces (`(1, uid)` for booking quota, `(2, uid)` for incident quota), and `5` `@Version` entities — paired with **6 real concurrency and Spring proxy bugs** caught and proved in PRs.
3. **The interleaved race diagram (`#310` / `#312`):** an inline Mermaid sequence diagram in `PROGRESS.md` traces a student booking colliding with an admin disabling that student's account at the same time. Even under a lock, Hibernate's L1 persistence cache would have kept `User(enabled = true)` if read with `findById()`, letting a banned user book; reading a scalar `findActiveRoleById` after acquiring the lock aborts the booking with `403 USER_DISABLED`, while `AFTER_COMMIT` listeners keep the account disabled, drop open SSE tabs immediately, and cancel upcoming bookings in isolated `REQUIRES_NEW` transactions.
4. **The 6 real bugs and two live demos anyone can run:**
   - Summarizes the 6 real PR-proved bugs (`#310`/`#312` L1 cache stale user read, `#327`/`#333` `default` interface methods bypassing `@Transactional` Spring proxies, `#270`/`#329`/`#336` AB/BA deadlocks and `@Version` batch rollbacks, `#112`/`#149` batch rollback after notification dispatch, `#319` concurrent damage + retire double-move, and `#263`/`#347` incident quota race + single-thread SSE heartbeat starvation) alongside their regression tests.
   - **Demo A (60-second CLI proof suite):** a collapsible command in `README.md` and a full breakdown table in `PROGRESS.md` running **75 live tests** on PostgreSQL 18 (`DemoFlowIT`, `SecurityPassIT`, ArchUnit rules, and all multi-thread concurrency, idempotency, rollback, and L1-bypass ITs — verified `75 / 75` in `41s`).
   - **Demo B (3-minute two-window browser demo):** 5 guided live steps with a student tab and an incognito admin tab showing atomic *"Reserve all"* + `409` overlap lock, `HIGH` damage auto-reassignment + instant SSE toast with zero page refresh, reliability metrics (`MTBF` / `vsModel`) + zero-write dry-run simulator, 30-second rotating QR pickup pass, and instant account disable kill-switch.

---

## Part 2: Technical details

### Files created and updated

| File | Action | Purpose |
|---|---|---|
| `README.md` | Updated | Added a concise "Why This Isn't a Simple CRUD App (And Can't Be One-Shotted)" section at the top, embedded `progress-beyond-crud.svg`, added the collapsible 60-second 75-test CLI proof command, and linked to `PROGRESS.md` §1 and the GitHub Wiki |
| `PROGRESS.md` | Updated | Added `§1. Why This Isn't a Simple CRUD App (And Can't Be One-Shotted)` (shifting former `§1`–`§9` to `§2`–`§10`) with both new SVGs, the `#310`/`#312` interleaved-race Mermaid `sequenceDiagram`, the 8-dimension engineering table, the 6 real PR bugs table, Demo A (75-test suite table), and Demo B (3-minute two-window browser walkthrough) |
| `docs/diagrams/progress-beyond-crud.svg` | Created | Pure-SVG side-by-side sketch comparing fragile one-shot CRUD prompt defaults with Campus Hub's verified systems engineering across 6 dimensions |
| `docs/diagrams/progress-lock-hierarchy.svg` | Created | Pure-SVG technical sketch of the PostgreSQL 18 lock-ordering hierarchy (Bands 0 and A–D across `10` `FOR UPDATE` queries, `2` advisory namespaces, and `5` `@Version` entities) alongside the 6 real concurrency/proxy bugs proved in PRs |
| `docs/diagrams/README.md` | Updated | Indexed `progress-beyond-crud.svg` and `progress-lock-hierarchy.svg` in the SVG table (Owner: Matei) |
| `documentation/Necula_Matei/66-beyond-crud-motivation.md` | Created | This note (Part 1 + Part 2) |
| `documentation/Necula_Matei/02-files-explained.md` | Updated | Added `progress-{beyond-crud,lock-hierarchy}.svg` and doc `66` to the header paragraph, tree, Part 1, and Part 2 |
| `documentation/Necula_Matei/README.md` | Updated | Updated row `65` PR column to `#359` and added row `66` pointing to this note |

### Methodology & Wiki auto-sync

| Topic | How it was produced / wired | Why |
|---|---|---|
| Standalone pure SVGs (`#fafbfc` light canvas) | Hand-crafted XML SVGs (`1200×740` and `1200×700`) with explicit `#fafbfc` light canvas, dot-grid background, and zero `<foreignObject>` tags | Guarantees readable contrast across both GitHub Light and Dark themes under GitHub's SVG sanitizer |
| Inline `#310`/`#312` Mermaid sequence diagram | Embedded `sequenceDiagram` in `PROGRESS.md` §1 tracing Student (`POST /reservations`), PostgreSQL 18 lock manager, Admin (`POST /admin/users/{id}/disable`), and `AFTER_COMMIT` listeners | Shows the exact lock wait, L1-cache pitfall, scalar JPQL bypass, SSE disconnect, and `REQUIRES_NEW` isolation without requiring an extra binary image |
| Automatic GitHub Wiki sync | `.github/workflows/sync-wiki.yml` already watches `PROGRESS.md` and `docs/diagrams/progress-*.svg` on `push` to `main` | Merging to `main` automatically rewrites both new `docs/diagrams/progress-*.svg` image paths (`raw.githubusercontent.com/.../main/...`) and all relative code/test links (`github.com/.../blob/main/...`) into Wiki `Home.md` |
| Grounded, runnable claims | Every metric, PR reference, class link, and the 75-test CLI command in `README.md` and `PROGRESS.md` matches verified code/tests on `main` and runs via one Gradle command | Lets the professor or any reviewer verify every systems-engineering claim in ~41 seconds on real PostgreSQL 18 |

### Links to teammates' modules and docs

- **Credits all four teammates' production and test work:**
  - **Liviu:** `catalog/`, `storage/` (`StorageValidation`), `notification/` (live-stream SSE `LiveStreams` and `UserDisabledLiveStreamListener`), and `common/error` (`GlobalExceptionHandler` RFC 9457 `ProblemDetail` + `code`).
  - **Calin:** `reservation/` (`IdempotencyService`, pessimistic/advisory locking, bundles/holds/transfers/overdue sweeps), `reassignment/` (`SameModelStrategy`, `SameCategoryStrategy`, `MaintenanceImpactSimulator`), `common/time` (`Clock`), ArchUnit enforcement (`ModuleBoundaryArchitectureTest`, `CodingConventionsArchitectureTest`), and concurrency ITs (`ReservationConcurrencyIT`, `IdempotencyServiceIT`, `AssetRetiredReassignmentIT`, `ReassignmentRollbackIT`).
  - **Stefania:** `incident/` (`ReliabilityCalculator`, `IncidentQuotaConcurrencyIT`), `user/admin/`, `common/security` (`CurrentUser`), frontend lead conventions, and `.github/workflows/ci.yml`.
  - **Matei:** `user/` (`AuthService`, `UserRepository.findActiveRoleById`, `PickupCodes`, `FindActiveRoleFreshReadIT`), `config/` (`SecurityConfig`), `project/` (`ProjectSearch`, `ProjectReservationService`), `infra/` (`Dockerfile`, Terraform, OIDC `deploy.yml`), `DemoFlowIT`, `SecurityPassIT`, and PIT mutation testing setup.
- **No teammate-owned files or sections modified:** only `README.md`, `PROGRESS.md`, `docs/diagrams/progress-*.svg`, `docs/diagrams/README.md`, and `documentation/Necula_Matei/` were edited; `docs/whats-left.md` and `AGENTS.md` §7 were left untouched (docs-only PR).

### Verification

| Check | Result |
|---|---|
| XML parse & zero-`<foreignObject>` check on both new SVGs | Passed (`XML OK` for `progress-beyond-crud.svg` and `progress-lock-hierarchy.svg`, explicit `#fafbfc` light canvas, zero `<foreignObject>` tags) |
| Headless Chromium full-height visual inspection | Inspected both SVGs at full viewport (`1260×860` / `1260×820`): zero clipped labels, zero overlapping shapes/text, clean light-canvas contrast |
| Live-demo CLI verification suite on scratch PostgreSQL 18 (`port 5433`) | `./gradlew test --no-daemon --rerun` passed **75 / 75 tests in 41s** (`DemoFlowIT` 10, `SecurityPassIT` 7, `CodingConventionsArchitectureTest` 21, `ModuleBoundaryArchitectureTest` 7, `ReservationConcurrencyIT` 13, `IdempotencyServiceIT` 7, `AssetRetiredReassignmentIT` 5, `ReassignmentRollbackIT` 3, `FindActiveRoleFreshReadIT` 1, `IncidentQuotaConcurrencyIT` 1); scratch cluster stopped and deleted cleanly |
| Relative Markdown link audit | Verified all image, code, test, and documentation links across `README.md`, `PROGRESS.md`, `docs/diagrams/README.md`, and `documentation/Necula_Matei/` |

# 65 — Engineering statistics, categorized LOC breakdown, and technical sketches

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-26
**See also:** `PROGRESS.md`, `docs/diagrams/README.md`, `.github/workflows/sync-wiki.yml`, `documentation/Necula_Matei/63-progress-page.md`, `documentation/Necula_Matei/64-wiki-sync-and-readme-nav.md`

---

## Part 1: In simple terms

1. **What we added to `PROGRESS.md`:** four new technical sketches and one inline endpoint chart that show how the project is engineered beneath the surface:
   - **Categorized Lines of Code (`progress-codebase-loc.svg`):** breaks down real, hand-written code by layer instead of showing one noisy total. It highlights that **test code is 2.16× larger than backend production code**, while explicitly leaving out auto-generated lockfiles (`package-lock.json`), static HTML prototypes, and binary files so nothing inflates the count.
   - **5-Layer Testing Pyramid + Concurrency Shield (`progress-testing-pyramid.svg`):** shows how we test the app across five layers — pure Mockito unit tests, `@WebMvcTest` web slice tests, `@DataJpaTest` JPA slice tests, full `@SpringBootTest` PostgreSQL integration/concurrency tests, and `@ArchTest` ArchUnit rules (plus on-demand PIT mutation testing) — alongside the locks and race tests that prevent double bookings.
   - **Engineering Process & PR Health (`progress-engineering-health.svg`):** summarizes how we collaborate day to day: small, reviewable pull requests, a **2.7-hour median open-to-merge cycle time**, and **89% of tracked issues closed**.
   - **REST API per-module chart (inline in `PROGRESS.md`):** shows how the 69 REST endpoints are spread across the backend modules at a glance.
   - **12-Table PostgreSQL 18 Data Map (`progress-domain-map.svg`):** maps all 12 database tables created by our Flyway migrations and how they link across users, catalog, reservations, incidents, reassignments, and audit logs.
2. **Why this matters for the FIS professor — without turning work into a commit race:** raw commit counts reward small housekeeping edits and hide the difference between a trivial tweak and a hard concurrency fix. Showing **test-to-code ratio (2.16×)**, **five testing layers**, **2.7h median PR cycle time**, **89% issue closure**, **API distribution**, and **clean schema boundaries** proves engineering quality, safety, and team discipline without pitting teammates against each other on commit volume.

---

## Part 2: Technical details

### Files created and updated

| File | Action | Purpose |
|---|---|---|
| `PROGRESS.md` | Updated | Added engineering statistics sections, inline Mermaid REST API per-module chart, and embedded the four new `progress-*.svg` sketches |
| `docs/diagrams/progress-codebase-loc.svg` | Created | Categorized LOC breakdown across tracked repository layers (showing 2.16× backend test-to-production ratio, excluding lockfiles, Gradle wrapper, HTML prototypes, and binary PNGs) |
| `docs/diagrams/progress-testing-pyramid.svg` | Created | 5-layer testing pyramid (Pure Unit, Web Slice, JPA Slice, Full Integration & Concurrency, ArchUnit + on-demand PIT callout) paired with the concurrency & locking shield |
| `docs/diagrams/progress-engineering-health.svg` | Created | Engineering process & PR health visual: PR size profile, 2.7h median open-to-merge cycle time, PR work-type mix, and 89% closed-issue ratio |
| `docs/diagrams/progress-domain-map.svg` | Created | Visual map of all 12 PostgreSQL 18 tables managed by Flyway migrations and their cross-module foreign-key relationships |
| `docs/diagrams/README.md` | Updated | Indexed the four new `progress-*.svg` diagrams with short descriptions and owner |
| `documentation/Necula_Matei/65-progress-engineering-stats.md` | Created | This note (Part 1 + Part 2) |
| `documentation/Necula_Matei/02-files-explained.md` | Updated | Added the four new `progress-*.svg` files and doc `65` to the header, tree, Part 1, and Part 2 |
| `documentation/Necula_Matei/README.md` | Updated | Added row `65` pointing to this note |

### Methodology & Wiki automation

| Topic | How it was produced / wired | Why |
|---|---|---|
| Categorized LOC (`git ls-files`) | Counted lines from Git-tracked source files grouped by category (backend production, backend tests, frontend, infra/CI, migrations/config, docs); explicitly excluded static HTML prototypes (`docs/prototype/*.html`, 2,073 lines), `frontend/package-lock.json` & Gradle wrapper (2,229 lines), and 62 binary PNGs | Prevents generated artifacts and mockups from skewing codebase numbers and exposes the real `2.16×` backend test-to-production ratio |
| PR cycle time, size & issues | Queried directly from GitHub PR and issue metadata (`createdAt` → `mergedAt` cycle times, diff sizes, open vs. closed issue counts → `2.7h` median open-to-merge time, `89%` closed issues) | Grounded in verifiable repository history rather than manual estimates |
| Standalone SVGs + inline Mermaid | Hand-crafted pure SVG (explicit `#fafbfc` light canvas, no `<foreignObject>`) for the four multi-panel sketches; inline Mermaid block in `PROGRESS.md` for the per-module REST API breakdown | Consistent light/dark rendering under GitHub's SVG sanitizer, while keeping endpoint counts easy to refresh as plain text |
| Automatic GitHub Wiki sync | `.github/workflows/sync-wiki.yml` already triggers on `push` to `main` for `PROGRESS.md` and `docs/diagrams/progress-*.svg` | Merging this PR automatically rewrites the four new image URLs to `raw.githubusercontent.com/.../main/...` and publishes the updated `Home.md` to the GitHub Wiki with zero manual duplication |

### Links to teammates' modules and docs

- **Reflects all four members' engineering work:** highlights Liviu's `catalog/`, `common/`, `storage/`, and `notification/` layers and tables; Calin's `reservation/`, `reassignment/`, `common/time`, ArchUnit rules, pessimistic/advisory locking, and race ITs; Stefania's `incident/`, `user/admin/`, `common/security`, frontend lead work, and `ci.yml`; and Matei's `user/`, `config/`, `project/`, `infra/`, `SecurityPassIT`, `DemoFlowIT`, and PIT setup.
- **No teammate-owned files or sections modified:** only Matei-owned progress showcase files, `docs/diagrams/progress-*.svg`, `docs/diagrams/README.md`, and `documentation/Necula_Matei/` were touched. Teammate sections in `docs/whats-left.md` and `AGENTS.md` §7 remain untouched.

### Verification

| Check | Result |
|---|---|
| XML parse check on all 4 new SVGs | Well-formed XML with explicit `#fafbfc` canvas and zero `<foreignObject>` or disallowed tags |
| Headless Chromium visual inspection | Rendered and checked all 4 SVGs (`progress-codebase-loc.svg`, `progress-testing-pyramid.svg`, `progress-engineering-health.svg`, `progress-domain-map.svg`) for contrast, spacing, alignment, and zero clipped or overlapping labels |
| Link & number audit | Verified all relative image and doc links across `PROGRESS.md`, `docs/diagrams/README.md`, and `documentation/Necula_Matei/`; cross-checked categorized LOC ratios (`2.16×`), PR/issue metrics (`2.7h` median open-to-merge time, `89%` closed), per-module API counts, and all 12 PostgreSQL 18 tables against the repository and GitHub metadata |

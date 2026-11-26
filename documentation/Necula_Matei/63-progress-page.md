# 63 — Progress page, contribution visuals and sketches

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-26
**Branch:** `docs/progress-page`
**See also:** `PROGRESS.md`, `README.md`, `docs/diagrams/README.md`, `docs/whats-left.md`, `docs/decisions.md`

---

## Part 1: In simple terms

1. **Why we moved §7 out of `README.md`:** `README.md` is the setup page — someone opening the repository for the first time wants to know what the app does and how to run it locally. As our project grew to 249 merged pull requests (up to #351), section 7 ("Progress: what each of us has done") made `README.md` very long and crowded. Moving it to a dedicated top-level `PROGRESS.md` keeps `README.md` short and focused on setup, while giving teammates and the FIS professor a clean front-page showcase of everything the team has built.
2. **Why the visuals show ownership and role mix, not a commit leaderboard:** counting raw commits is misleading on a team project. Small housekeeping commits (weekly status updates, CI tweaks, dependency bumps, doc fixes) inflate one person's commit count even when another teammate spent three days building a complex booking or incident flow. Instead of turning contributions into a commit race, the visuals show **who owns each part of the app**, **each member's mix of work** (backend, frontend, tests, docs, cloud/CI), and **how the four of us review each other's PRs**.
3. **What the 3 sketches and 2 charts show:**
   - **System overview sketch (`progress-system-overview.svg`):** a bird's-eye picture of the whole Campus Equipment Hub — student and admin pages, backend modules, PostgreSQL database, and AWS deployment — color-coded by owner.
   - **Module ownership sketch (`progress-module-ownership.svg`):** who owns each backend module, shared service, frontend section, and workflow, plus how the modules talk to each other across agreed boundaries.
   - **Work profile & peer-review sketch (`progress-work-profile.svg`):** each team member's 100%-normalized role mix across backend, frontend, tests, documentation, and infrastructure/CI on the left, paired with a 4×4 cross-team peer-review heatmap (283 formal reviews, 0 solo merges) on the right.
   - **Two inline Mermaid charts (in `PROGRESS.md`):** a project timeline with milestones and a weekly delivery pace chart, written as plain text so GitHub draws them automatically and we can update them in seconds without redrawing images.

---

## Part 2: Technical details

### Files created and updated

| File | Action | Purpose |
|---|---|---|
| `PROGRESS.md` | Created | Dedicated top-level team progress showcase: ownership overview, embedded SVG sketches, 2 inline Mermaid charts (timeline + weekly pace), and collapsed `<details>` per-member PR tables updated through `fa0002d` |
| `docs/diagrams/progress-system-overview.svg` | Created | Hand-crafted SVG architecture & product overview color-coded by team member |
| `docs/diagrams/progress-module-ownership.svg` | Created | Hand-crafted SVG map of backend modules, shared services, frontend areas, and cross-module contracts per owner |
| `docs/diagrams/progress-work-profile.svg` | Created | Two-panel SVG: 100%-normalized role mix per member (instead of a raw commit leaderboard) + 4×4 cross-team peer-review heatmap |
| `README.md` | Updated | Refreshed "What works in the browser today" (L45) and top Status callout to 26 Nov / W8 (L81); replaced the long inline §7 tables with a short summary, milestone table, and prominent link to `PROGRESS.md` |
| `docs/diagrams/README.md` | Updated | Indexed the three `progress-*.svg` sketches with descriptions and owner |
| `docs/decisions.md` | Updated | Added the `2026-11-22` Schedule row recording the teacher feedback demo move from Mon 23 Nov to Mon 7 Dec |
| `docs/whats-left.md` | Updated (line 3 only) | Pointed the top "already done" reference from `` `README.md` §7 `` to `PROGRESS.md` |
| `AGENTS.md` | Updated (line 155 only) | Noted that Matei's weekly refresh covers `PROGRESS.md` alongside the README status |
| `documentation/Necula_Matei/63-progress-page.md` | Created | This note (Part 1 + Part 2) |
| `documentation/Necula_Matei/02-files-explained.md` | Updated | Added entries for `PROGRESS.md` and the three `progress-*.svg` sketches |
| `documentation/Necula_Matei/README.md` | Updated | Added row `63` and updated the Team docs link from `README.md §7` to `PROGRESS.md` |

### Key design choices

| Choice | Why |
|---|---|
| Explicit `#fafbfc` canvas on every SVG | GitHub dark mode displays transparent SVGs over a dark background, making dark ink unreadable. An explicit light `#fafbfc` canvas guarantees consistent contrast in both GitHub light and dark themes. |
| Pure SVG primitives, no `<foreignObject>` | GitHub's SVG sanitizer strips `<foreignObject>`, scripts, and external font loads. Using only standard `<rect>`, `<line>`, `<path>`, `<circle>`, `<text>`, and system font stacks guarantees clean rendering on GitHub without broken layout. |
| Inline Mermaid for timeline and weekly pace | Milestones and weekly counts change every week. Keeping those two charts as inline `mermaid` blocks in `PROGRESS.md` makes weekly refreshes a small text diff rather than regenerating static image files. |
| Collapsed `<details>` PR tables + 21 missing PRs added | Keeps the top of `PROGRESS.md` scannable for the professor and teammates while preserving the full PR history per member, brought up to date with 21 previously missing merged PRs through commit `fa0002d`. |
| Single source of truth (+ optional Wiki pointer) | `PROGRESS.md` lives in Git on `main` so every update goes through normal PR review and stays in sync with git history. If the GitHub Wiki tab is enabled later, a 1-paragraph Wiki home page can simply link to `PROGRESS.md` rather than duplicating tables that drift out of date. |

### How it connects to teammates' modules and docs

- **All four members covered without stepping on ownership:** `PROGRESS.md` and the three SVGs present Matei's (`user/`, `config/`, `project/`, `infra/`), Liviu's (`catalog/`, `common/`, `storage/`, `notification/`), Calin's (`reservation/`, `reassignment/`, `common/time`, ArchUnit), and Stefania's (`incident/`, `user/admin/`, `common/security`, frontend lead, CI) modules, contracts, and merged PRs.
- **No teammate-owned sections modified:** every PR entry and ownership boundary was verified directly from git history (`fa0002d`) and GitHub PR metadata. Teammate-owned sections in `docs/whats-left.md` and `AGENTS.md` §7 were left untouched; only the shared top-level pointers owned by Matei were updated.

### How it was verified

| Check | Result |
|---|---|
| XML parse check on all 3 SVGs | Well-formed XML, zero `<foreignObject>` elements or disallowed tags |
| Headless Chromium visual render inspection | All 3 SVGs rendered and inspected for contrast, alignment, spacing, and no clipped or overlapping labels |
| Internal link audit | Verified all relative links across `PROGRESS.md`, `README.md`, `docs/diagrams/README.md`, `docs/whats-left.md`, and `documentation/Necula_Matei/README.md` |
| PR-number audit against `main` (`fa0002d`) | Cross-checked merged PRs per author against git/GitHub history; 21 missing PRs added, no duplicates or misattributed PRs |

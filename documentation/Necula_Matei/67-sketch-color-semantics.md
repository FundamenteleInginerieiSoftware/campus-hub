# 67 — Semantic color alignment in the beyond-CRUD and lock-hierarchy sketches

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-26
**See also:** `PROGRESS.md`, `README.md`, `docs/diagrams/README.md`, `.github/workflows/sync-wiki.yml`, `documentation/Necula_Matei/66-beyond-crud-motivation.md`

---

## Part 1: In simple terms

1. **Why we adjusted the colors in both sketches:** earlier progress sketches (`progress-system-overview.svg`, `progress-module-ownership.svg`, `progress-work-profile.svg`, and `progress-domain-map.svg`) assign **Blue**, **Teal**, **Amber**, and **Violet** to the four teammates. When `progress-beyond-crud.svg` and `progress-lock-hierarchy.svg` first landed in `#360`, rows and bug cards used mixed colors without a key, which could mislead a reader into thinking a row or bug belonged to a specific teammate rather than a technical concept.
2. **Strict Red → Emerald polarity in `progress-beyond-crud.svg`:** every left-side fragile prompt card is now **Red**, while every center arrow (`01 LOCK`–`06 CLOUD`), right-side Campus Hub engineering card, and the top-right test pill (`1,221 tests · 2.16x test-to-prod`) is **Emerald**. An explicit **Color key** in the header spells out: `Red = one-shot CRUD default · Emerald = verified in Campus Hub (not member ownership)`.
3. **1-to-1 band-to-bug pairing in `progress-lock-hierarchy.svg`:** header chrome (accent bar, `Deadlock-free lock DAG` badge, and panel header icons) is now neutral **Slate**, and an explicit header legend explains that color links each lock flow on the left to the real PR bug it prevents on the right (not member ownership). The six right-hand bug cards are reordered into exact **`0 → A → B → B → C → D`** order with matching colors and `BAND` badges:
   - `BAND 0` (**Slate**) → Batch Rollback & Spring Proxy Bypass (`PR #112 / #149 / #327`)
   - `BAND A` (**Amber**) → Hibernate L1 Stale User Cache (`PR #310 / #312`)
   - `BAND B` (**Blue**) → AB/BA Deadlocks & `@Version` Rollbacks (`PR #270 / #329`) and Concurrent Damage + Retire Double-Move (`PR #319`)
   - `BAND C` (**Violet**) → Parallel Incident Quota Race (`PR #263`)
   - `BAND D` (**Teal**) → Single-Thread Scheduler Starving SSE (`PR #347`)

---

## Part 2: Technical details

### Files updated and created

| File | Action | Purpose |
|---|---|---|
| `docs/diagrams/progress-beyond-crud.svg` | Updated | Unified right-column cards, center `01`–`06` connector badges/arrows, and top-right summary pill to Emerald (`#059669`), unified left-column cards to Red (`#dc2626`), neutralized the Sky highlight on `29,619` in the bottom proof strip, and added the header `Color key` |
| `docs/diagrams/progress-lock-hierarchy.svg` | Updated | Expanded viewBox to `1200×714`, switched header accent bar, `Deadlock-free lock DAG` pill, and panel header icons to neutral Slate, added the 5-dot semantic color legend, reordered the 6 bug cards to `BAND 0 → A → B → B → C → D`, and added matching `BAND` badges and 1-to-1 band colors |
| `documentation/Necula_Matei/67-sketch-color-semantics.md` | Created | This note (Part 1 + Part 2) |
| `documentation/Necula_Matei/02-files-explained.md` | Updated | Added `67-sketch-color-semantics.md` to the header paragraph, file tree (`progress-{beyond-crud,lock-hierarchy}.svg`), Part 1, and Part 2 |
| `documentation/Necula_Matei/README.md` | Updated | Updated row `66` PR column from `—` to `#360` and added row `67` pointing to this note |

### Semantic color mapping & Wiki sync

| Sketch | Previous ambiguity | New semantic encoding | Wiki & GitHub sync |
|---|---|---|---|
| `progress-beyond-crud.svg` (`1200×740`) | Per-row Amber / Teal / Blue / Violet / Sky / Emerald right cards, Blue top-right pill, and Sky `29,619` figure overlapped teammate colors without a legend | **Red** (`#dc2626` / `#fef2f2`) = one-shot CRUD default; **Emerald** (`#059669` / `#ecfdf5` / `#f0fdf4`) = verified in Campus Hub (`01 LOCK`–`06 CLOUD`, 6 engineering cards, and `1,221 tests · 2.16x test-to-prod` pill) + explicit header `Color key` | `.github/workflows/sync-wiki.yml` watches `docs/diagrams/progress-*.svg` on `push` to `main`, so merging automatically refreshes the GitHub Wiki images |
| `progress-lock-hierarchy.svg` (`1200×714`) | Amber header chrome and unaligned right-card colors (`#310/#312` Blue, `#327/#333` Violet, `#270/#329` Amber, `#112/#149` Teal, `#319` Red, `#263/#347` Emerald) lacked `BAND` tags and a color key | Neutral **Slate** chrome + 5-dot header legend; left-to-right 1-to-1 band color & `BAND` badge alignment: **BAND 0** Slate (`#475569`) → `#112/#149/#327`; **BAND A** Amber (`#d97706`) → `#310/#312`; **BAND B** Blue (`#2563eb`) → `#270/#329` & `#319`; **BAND C** Violet (`#7c3aed`) → `#263`; **BAND D** Teal (`#0d9488`) → `#347` (`LiveStreamIT`) | Same `sync-wiki.yml` trigger updates `Home.md`'s embedded image automatically on merge to `main` |

### Links to teammates' modules and docs

- **Clarifies cross-team concurrency & reliability proofs without editing teammate files:** `progress-lock-hierarchy.svg` spans **Calin's** `reservation/` + `reassignment/` (`IdempotencyService`, `REQUIRES_NEW` per-booking isolation, `Reservation → Asset → Transfer` lock ordering, `expectedSourceAssetId` guard, and the ArchUnit ban on `default` methods in `..api..`), **Liviu's** `notification/` (`LiveStreams` / `LiveStreamIT`), **Stefania's** `incident/` (`IncidentQuotaConcurrencyIT`) and `user/admin/`, and **Matei's** `user/` (`findActiveRoleById` / `FindActiveRoleFreshReadIT`) and 3-thread `campus-sched-` pool (`#347`).
- **Scope guard:** only the two `docs/diagrams/progress-*.svg` files and `documentation/Necula_Matei/` were touched; `docs/whats-left.md`, `AGENTS.md` §7, and all teammate-owned files remain untouched.

### Verification

| Check | Result |
|---|---|
| XML well-formedness & forbidden-tag check on both SVGs | Passed (`progress-beyond-crud.svg` and `progress-lock-hierarchy.svg` parse cleanly as XML with explicit `#fafbfc` canvas and zero `<foreignObject>` or `<script>` tags) |
| Headless Chromium full-height visual inspection (`1260×860` / `1260×840`) + DejaVu bounding-box clearance check | Verified clean alignment, legibility, contrast, and zero text/shape overlaps or clipping across headers, color legends, chips, and `BAND` badge pairs |
| Relative Markdown link audit | Verified all relative file and doc references across `documentation/Necula_Matei/67-sketch-color-semantics.md`, `documentation/Necula_Matei/02-files-explained.md`, and `documentation/Necula_Matei/README.md` |

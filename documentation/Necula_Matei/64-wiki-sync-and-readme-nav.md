# 64 — README top navigation bar and automatic GitHub Wiki sync

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-26
**See also:** `README.md`, `PROGRESS.md`, `.github/workflows/sync-wiki.yml`, `documentation/Necula_Matei/63-progress-page.md`

---

## Part 1: In simple terms

1. **Why we couldn't add a "Progress" tab next to README:** GitHub's tab strip above the README box only shows built-in community files (`README`, `Contributing`, `Code of conduct`, `License`, `Security`). It cannot display a custom `PROGRESS.md` tab there.
2. **What we put at the top of `README.md`:** a single navigation bar right under the project title with direct links to **Project Progress & Team Contributions**, **Wiki**, **What's Left**, and **Contributing**. Anyone opening the repository sees those links immediately without scrolling.
3. **Why we also sync `PROGRESS.md` to the GitHub Wiki:** GitHub does give the repository a top-level **Wiki** tab in the main header. Instead of copying and maintaining a second progress page by hand (which would drift out of date), a small GitHub Actions workflow (`sync-wiki.yml`) copies `PROGRESS.md` into the Wiki's `Home` page automatically whenever `PROGRESS.md` or its diagrams change on `main`. `PROGRESS.md` stays the single source of truth reviewed through normal pull requests, with zero manual duplication.

---

## Part 2: Technical details

### Files created and updated

| File | Action | Purpose |
|---|---|---|
| `README.md` | Updated | Added a top navigation blockquote directly below `# Campus Equipment & Incident Hub` linking to `PROGRESS.md`, the GitHub Wiki, `docs/whats-left.md`, and `CONTRIBUTING.md` |
| `docs/diagrams/progress-system-overview.svg` | Updated | Added subtle cross-team credit line (`uses Calin's bundles · Liviu's kit seed`) on the `project` module card |
| `.github/workflows/sync-wiki.yml` | Created | GitHub Actions workflow that transforms `PROGRESS.md` into `Home.md` in the repository's `.wiki` git repo on merges to `main` or manual dispatch |
| `documentation/Necula_Matei/64-wiki-sync-and-readme-nav.md` | Created | This note (Part 1 + Part 2) |
| `documentation/Necula_Matei/02-files-explained.md` | Updated | Indexed `.github/workflows/sync-wiki.yml` in the tree, Part 1, and Part 2 |
| `documentation/Necula_Matei/README.md` | Updated | Added row `64` pointing to this note |

### Workflow design (`.github/workflows/sync-wiki.yml`)

| Aspect | Setting / Behavior | Why |
|---|---|---|
| Triggers | `push` to `main` on `PROGRESS.md`, `docs/diagrams/progress-*.svg`, or `.github/workflows/sync-wiki.yml`; plus `workflow_dispatch` | Runs only when the progress showcase, its SVG sketches, or the sync script itself changes on `main` (never on PRs), and can be triggered manually after first enabling the Wiki |
| Concurrency | `group: sync-wiki`, `cancel-in-progress: false` | Serializes pushes to the `.wiki` repository so back-to-back merges to `main` never race on `git push` |
| Permissions | `contents: write` | Grants the default `GITHUB_TOKEN` permission to push to `${{ github.repository }}.wiki` without storing a personal access token |
| Checkouts | Main repo into `repo/` (`persist-credentials: false`); `${{ github.repository }}.wiki` into `wiki/` with `GITHUB_TOKEN` | Separates the source tree from the wiki git repo and avoids leaving credentials on the source checkout |
| Link rewriting (Python 3) | `![alt](path)` → `https://raw.githubusercontent.com/{repo}/main/{path}` · `[text](path/)` → `https://github.com/{repo}/tree/main/{path/}` · `[text](path)` → `https://github.com/{repo}/blob/main/{path}` · skips `http://`, `https://`, `mailto:`, `#` | The Wiki lives in a separate `.wiki` git repo, so relative paths to `docs/diagrams/progress-*.svg`, `docs/whats-left.md`, `README.md`, and `documentation/` would break unless rewritten to absolute `main` URLs; `GITHUB_REPOSITORY` is passed via `env`, never interpolated into shell text |
| Sync banner | Inserted immediately after the first `# ` heading: `> **Auto-synced from [\`PROGRESS.md\`](...) on \`main\`.** Edit \`PROGRESS.md\` via pull request to update this page.` | Tells readers not to edit the Wiki directly so changes never get overwritten on the next sync |
| Idempotent commit | Sets `github-actions[bot]` identity, stages `Home.md`, checks `git diff --cached --quiet Home.md`, commits `"Sync Wiki Home from PROGRESS.md"` and pushes only on actual changes | Avoids empty commits and failing runs when a `workflow_dispatch` or SVG-only update produces identical `Home.md` content |

### Links to teammates' modules and docs

- **No teammate-owned files modified:** Stefania's `.github/workflows/ci.yml` is untouched; `sync-wiki.yml` runs independently after merges to `main` and is not a PR check. No teammate sections in `docs/whats-left.md` or `AGENTS.md` §7 were edited.
- **Benefits all four members:** every teammate's section in `PROGRESS.md` (Matei, Liviu, Calin, Stefania) and links to shared docs (`docs/whats-left.md`, `docs/diagrams/`, `docs/api-contract.md`, `documentation/`) stay accessible both from the top of `README.md` and from the repository's **Wiki** tab.

### Verification

| Check | Result |
|---|---|
| `README.md` top navigation link audit | Verified all four targets (`PROGRESS.md`, `https://github.com/FundamenteleInginerieiSoftware/campus-hub/wiki`, `docs/whats-left.md`, `CONTRIBUTING.md`) sit directly under `# Campus Equipment & Incident Hub` |
| Workflow YAML & Python script inspection | Confirmed valid workflow schema, `env`-passed `GITHUB_REPOSITORY`, relative image/directory/file link rewriting against every link form in `PROGRESS.md`, preserved `http(s)://`/`mailto:`/`#` links, and idempotent `git diff --cached --quiet Home.md` guard |
| End-to-end Wiki push | Runs on GitHub Actions once merged to `main` (or via `workflow_dispatch` after the Wiki has its initial page initialized) |

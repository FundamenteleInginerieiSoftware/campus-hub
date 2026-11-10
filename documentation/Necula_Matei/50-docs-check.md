# End-of-task docs: the rule and the check (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-10
**Branch / PR:** `docs/agents-end-of-task-docs` (#277), `ci/docs-check` (#279)
**See also:** `AGENTS.md` §4 "End of every task", `.github/workflows/docs-check.yml`

---

## Part 1: In simple terms

1. **Problem:** almost all the updates to the project status (what is done, what is left) were written by me: 24 of the 25 changes to those files. Teammates' AI assistants did the work but did not write it down, so I spent time and tokens writing up their progress.
2. **The rule (#277):** the shared instructions every AI reads (`AGENTS.md`) now say: at the end of every task, in the same pull request, update your personal notes, your part of the "what's left" list, and your line in the project status.
3. **Why a rule is not enough:** an AI can skip it, and some tools never read `AGENTS.md` at all (Gemini reads `GEMINI.md`, Copilot reads its own file).
4. **The check (#279):** a small robot on GitHub looks at every pull request. If it changes the app but not those three documents, it turns red and lists what is missing. It does not care which AI (or person) wrote the code.
5. **Pointer files:** `GEMINI.md` and `.github/copilot-instructions.md` tell those two tools to follow `AGENTS.md`.
6. **Exceptions:** a teammate can add the label `no-docs` to a pull request that really needs no notes (a one-line fix, a library update).

**Result:** every pull request that changes the app either brings its own status update or shows a red check, which reviewers see.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `AGENTS.md` | §4 "End of every task" block (#277) + the `docs-check` paragraph; §4 Definition of Done and PR checklist; §6 one doc per PR; §7 ruleset line; §11 members append their own §7 lines |
| `.github/pull_request_template.md` | Checkbox for the end-of-task docs (#277) |
| `.github/workflows/docs-check.yml` | New job `docs-check` |
| `GEMINI.md` | New: imports `AGENTS.md` (`@./AGENTS.md`) for Gemini CLI |
| `.github/copilot-instructions.md` | New: points GitHub Copilot to `AGENTS.md` §4 |

### How the check decides

| Case | Result |
|---|---|
| Label `no-docs`, or the author is Dependabot | Pass, check skipped |
| No file under `backend/src/main/` or `frontend/src/` changed (docs, tests, build, CI) | Pass |
| App code changed, plus a file under `documentation/*/`, `docs/whats-left.md` and `AGENTS.md` | Pass |
| App code changed, one of the three missing | Fail; one `::error` annotation per missing item, with what to write |

### Why it is built this way

- **Separate workflow, not a step in `ci.yml`:** `ci.yml` is Stefania's, and this job needs no database or Gradle, so it finishes in seconds.
- **Three-dot diff (`base...head`):** only what this PR changed since it left `main`, not what `main` gained meanwhile.
- **PR data through `env`:** the author name and SHAs are read from environment variables, never pasted into the script, so a strange branch name or title cannot run as shell code.
- **Only own lines:** the shared status lines (§7 date line, "Open PRs" table, README status) stay with the weekly refresh. If every PR edited them, every open PR would conflict on the same lines (as the doc 02 header and README index rows did in November).
- **Required since 10 Nov 2026:** at first it only showed red without blocking. Matei then added `docs-check` to the required checks of the `protect-main` ruleset (next to `ci`), so a PR that changes app code without its end-of-task docs can't be merged. The job runs on every PR (no path filter) and passes at once when no app code changed, so docs-only PRs are never stuck waiting for it. PRs opened before #279 never ran it: the next push or "Update branch" starts it.

### How it was tested

The script was run locally against real past PRs:

| Range | Expected | Got |
|---|---|---|
| #273 (code + personal doc, no `whats-left`/`AGENTS.md`) | fail, 2 items missing | exit 1, both listed |
| #276 (docs only) | pass, nothing to check | exit 0 |
| #273 with `NO_DOCS=true` | pass, skipped | exit 0 |

The YAML parses (`yaml.safe_load`). On GitHub the job runs on this PR itself (no app code changed, so it passes).

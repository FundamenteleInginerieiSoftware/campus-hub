Instructions for GitHub Copilot in this repo live in `AGENTS.md` at the repository root (shared by every AI assistant the team uses).

Read `AGENTS.md` at the start of every task and follow it. Before the user asks for review, do section 4, "End of every task": in the same PR, update the user's personal doc in `documentation/<Surname_Name>/`, their section of `docs/whats-left.md`, and their module's bullet in section 7 of `AGENTS.md`. The `docs-check` CI job fails the PR when app code changed without them.

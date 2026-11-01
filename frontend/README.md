# Campus Hub frontend

React + Vite + TypeScript, UI kit [Mantine](https://mantine.dev), routing with React Router. Owner: Stefania.

| Task | Command (run in `frontend/`) |
|---|---|
| Install (first time, or after `package.json` changes) | `npm ci` |
| Dev server on http://localhost:5173 | `npm run dev` (start the backend first: `gradlew bootRun`) |
| Lint (CI runs it) | `npm run lint` |
| Production build into `dist/` (CI runs it) | `npm run build` |

In dev, Vite forwards every `/api/...` call to the backend on `localhost:8080`, so no CORS setup is needed.

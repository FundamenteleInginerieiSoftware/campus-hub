# Dockerfile: the whole app in one image (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-04
**Branch / PR:** `infra/dockerfile`
**See also:** `09-aws-architecture.md` (ADR-002: one EC2, Spring serves the React build), `04-password-jwt-roles.md` (security rules)

---

## Part 1: In simple terms

On AWS the app runs as one "container": a sealed box with Java and our program inside. The Dockerfile is the recipe for that box.

1. **The recipe builds the website first.** It turns Stefania's React code into plain files (one HTML page, a few JavaScript and CSS files).
2. **Then it builds the backend and puts the website inside it.** One program now answers both: `/api/...` gives data, every other address gives the website.
3. **Only what is needed to run goes into the final box:** Java (without the compiler) and our program. The build tools stay behind, so the box is small (about 290 MB) and has fewer things that could be attacked.
4. **The program inside does not run as the all-powerful "root" user.** If someone ever broke in, they could not change the box itself.
5. **Reloading a page works.** Pages like "My reservations" exist only inside the website. When you reload one, the server now sends the website back, and the website shows the right page.
6. **The website itself is public, the data is not.** Anyone can load the login page, as on any site, but every piece of data still needs a login token.
7. **Docker checks every 30 seconds that the app is healthy.** Our deploy script (W8) will use this to know a new version started well.

**Result:** `docker build -t campus-hub .` makes the image that EC2 will run. Tested locally: the website, its pages, login and the API all work from one address.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `Dockerfile` (repo root) | New: 3-stage build (Node → JDK + Gradle → JRE) |
| `.dockerignore` | New: keeps `.git`, `node_modules`, build output, `.env` files, docs out of the build |
| `config/SpaConfig.java` | New: serves `static/` and falls back to `index.html` for React Router pages |
| `config/SecurityConfig.java` | `GET` outside `/api` is public; `/api/**` and `/actuator/**` outside the listed rules stay `denyAll()` |
| `test/.../config/SpaFrontendTest.java` | New: 4 tests |
| `test/.../config/SecurityConfigTest.java` | Old "paths outside the API are denied" test split into 2 (other `/api` path, `POST` outside the API) |
| `test/resources/static/index.html` | New: stand-in for the React build in tests |
| `02-files-explained.md` | Rows for the new files |

### The 3 stages

| Stage | Base image | What it does | Why |
|---|---|---|---|
| `frontend` | `node:24-slim` | `npm ci`, `npm run build` → `dist/` | Node 24 like `ci.yml`. Debian slim, not Alpine: Vite's native build binaries are built for glibc |
| `backend` | `eclipse-temurin:25-jdk` | `./gradlew dependencies` (cached layer), copy `src`, copy `dist` into `src/main/resources/static`, `./gradlew bootJar` | Build files are copied before the code, so Docker reuses the download layer until `build.gradle` changes. `bootJar` skips tests: CI already ran them, and they need a database |
| final | `eclipse-temurin:25-jre-alpine` | Copies only `app.jar` | No compiler, no Gradle, no Node: 293 MB image. Non-root user `app`. `SPRING_PROFILES_ACTIVE=prod`, `EXPOSE 8080`, `HEALTHCHECK` with `wget` on `/actuator/health`, `-XX:MaxRAMPercentage=75` so the heap fits the container |

### Key choices

| Choice | Why |
|---|---|
| Dockerfile at the repo root, not in `backend/` | The build needs both `frontend/` and `backend/`; Docker can only copy files inside its build folder |
| React files copied into `src/main/resources/static` **inside the build stage** | Nothing changes in git: in dev `static/` stays empty and Vite serves the frontend (ADR-002) |
| `SpaConfig` with a `PathResourceResolver`, not a controller | A controller would need every React route listed, or a regex that Spring's `PathPattern` can't express for several segments. The resolver runs only for paths no controller mapped, so it can't hide a real endpoint |
| No `index.html` for `api/...` paths or paths with a dot | An unknown API path must stay a JSON 404; a missing `/assets/old.js` must be a 404, not HTML the browser can't run |
| Security: `GET /**` → `permitAll()`, placed **after** the API rules and after a new `"/api/**", "/actuator/**"` → `denyAll()` | First match wins. The new `denyAll` keeps `/api/v2/...` and `/actuator/env` closed even though the GET rule comes after it. The React files hold no data, so making them public changes nothing about who sees data |
| `-XX:MaxRAMPercentage=75` | t3.small has 2 GB; the rest is left for the JVM's own memory and the OS |
| `HEALTHCHECK` | `docker ps` shows `healthy`/`unhealthy`; `deploy.yml` (W8) waits for `healthy` after a restart |

### How it was tested

| Check | Result |
|---|---|
| `./gradlew spotlessApply build --rerun` (scratch PostgreSQL 18) | BUILD SUCCESSFUL, 353 tests |
| `docker build -t campus-hub:local .` | OK in ~1m45s (first run), image 293 MB |
| `docker run --network host -e SPRING_PROFILES_ACTIVE=dev -e DB_URL=... -e SERVER_PORT=8186 campus-hub:local` | Started, Flyway ran, `/actuator/health` UP, `docker inspect` → healthy |
| `curl /`, `/reservations`, `/admin/incidents` | 200 `text/html` (the React app) |
| `curl /assets/index-<hash>.js`, `/favicon.svg` | 200 `text/javascript`, `image/svg+xml` |
| `curl /assets/nope.js` | 404 `application/problem+json` |
| `curl /api/v1/nope`, `/api/v2/x`, `/actuator/env` (no token) | 401 `application/problem+json` |
| `POST /api/v1/auth/login` (student1) | 200 with `accessToken` |
| `docker exec ... id` | `uid=100(app)`, not root |

### Known limitation (fixed): `prod` didn't start

With `SPRING_PROFILES_ACTIVE=prod` the app used to stop at startup: `ReassignmentService required a bean of type NotificationService`. In `prod` Liviu's dev adapters are off (`@Profile("!prod")`), and the AWS versions were missing. Fixed by `S3StorageService` + `SesNotificationService` (#115, `23-s3-ses-adapters.md`, which also lists the env variables `prod` needs). Not a Dockerfile bug: the image was right, the prod beans were missing.

### Next

| Step | Week |
|---|---|
| ECR repository (Terraform part 2, #250), push the image (first push done 9 Nov) | W6 ✅ |
| `S3StorageService` + `SesNotificationService`, so `prod` starts | W8 |
| `deploy.yml`: build, push with the commit SHA, SSM Run Command, wait for `healthy` | W8 |

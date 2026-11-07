# Component and deployment diagrams (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-05
**Branch / PR:** `docs/diagrams-deployment-component`
**See also:** `09-aws-architecture.md` (ADRs + draw.io picture), `19-dockerfile.md` (the image), `23-s3-ses-adapters.md` + `29-s3-ses-hardening.md` (S3/SES), `15-archunit-dependency.md` (the module rule tests)

---

## Part 1: In simple terms

1. **The component diagram is a map of the program's parts.** The backend is split into modules (users, catalog, reservations, incidents, reassignment, photos, e-mails, shared tools). Each module offers a "front door" (its `api`), and the arrows show which module uses which front door. The arrows were taken from the real code, not from memory.
2. **It also shows two design patterns the course asks about.** *Observer*: when an incident is reported, the incident module only announces "this asset is damaged"; the reassignment module listens and moves the bookings. *Strategy*: the reassignment engine tries "same model" first, then "same category", and cancels the booking if neither finds a free item.
3. **The local deployment diagram shows how we run the app on our laptops:** the browser talks to the Vite server, which passes API calls to Spring Boot, which stores data in PostgreSQL. It also shows GitHub's automatic check (`ci`), which runs every test against its own throwaway PostgreSQL.
4. **The AWS deployment diagram shows how the app will run on AWS** (planned for weeks 7–9, not deployed yet): the browser reaches CloudFront over HTTPS, CloudFront forwards to one server that runs our Docker container, and the container uses a private database (RDS), a photo bucket (S3), an e-mail service (SES) and a safe for passwords (SSM Parameter Store).
5. **Every arrow on the deployment diagrams says how the two sides talk** (for example "HTTPS :443" or "JDBC :5432"), and every box says what it is (a machine, a program that runs other programs, or a file).

**Result:** three UML diagrams in `docs/diagrams/` that match the code on `main` (5 Nov 2026) and the ADRs, ready for the report and the defense.

---

## Part 2: Technical details

### Files

| File | What it is |
|---|---|
| `docs/diagrams/component.puml` + `.png` | UML component diagram of `com.campus.*`: modules as `<<module>>` components, `api` facades as provided interfaces, required dependencies as dashed arrows, `AssetDamagedEvent` as `<<event>>`, notes for Observer and Strategy, legend with the ArchUnit rules |
| `docs/diagrams/deployment-local.puml` + `.png` | UML deployment diagram: laptop (`<<device>>`) with browser, Node/Vite :5173, Spring Boot JVM :8080 (profile `dev`), Gradle test JVM (profile `test`), PostgreSQL 18 :5432 (`campus`, `campus_test`), `uploads/`; GitHub Actions runner with the `ci` job and the `postgres:18` service container |
| `docs/diagrams/deployment-aws.puml` + `.png` | UML deployment diagram of the planned prod setup: CloudFront → EC2 `t3.small` → Docker → container (`app.jar`, profile `prod`, :8080) → RDS PostgreSQL 18; S3, SES, SSM, ECR, CloudWatch Logs; IAM instance role; deploy path from GitHub Actions |
| `docs/diagrams/README.md` | One row per diagram in the folder (new file) |

### How the module dependencies were derived

From `backend/src/main/java/com/campus`, every cross-module import, grouped by source module and target package:

```bash
cd backend/src/main/java/com/campus
grep -rn "^import com\.campus\." . \
  | sed -E 's#^(\./)?([a-z]+)/[^:]*:[0-9]+:import (static )?com\.campus\.([a-z]+)\.([a-zA-Z]+).*#\2 \4.\5#' \
  | awk '{split($2,t,"."); if ($1!=t[1]) print $1" -> "$2}' | sort | uniq -c
```

Result on `main` (`99d0261`), without `common.error` / `common.paging` (used by almost everyone):

| Module | Requires |
|---|---|
| `config` | `user.api` (`SecurityConfig` → `UserApi.findActiveRole`) |
| `user` | `common.security` |
| `catalog` | `common.audit`, `common.security` |
| `reservation` | `catalog.api`, `notification.api`, `user.api` (only `ReservationSeeder`, dev), `common.audit`, `common.security` |
| `incident` | `catalog.api`, `common.security` (publishes `incident.api.AssetDamagedEvent`) |
| `reassignment` | `catalog.api`, `reservation.api`, `notification.api`, `incident.api` (listener) |
| `notification` | `user.api` (only `SesNotificationService`, prod) |
| `storage` | `common.error` only; **no module uses `StorageService` yet** (incident photo upload is not on `main`) |

The `Clock` bean (`common.time`) is injected as `java.time.Clock`, so it never shows up as a `com.campus` import; its users were found with `grep -rln "Clock clock"`. A second grep for imports of another module's `domain|repository|service|web` packages found **none**: no module-rule violation on `main`. `messaging/` only has a `package-info.java` (SQS stretch goal), so it is mentioned in the legend, not drawn.

### Re-rendering

PlantUML is not part of the build. Download the jar once (outside the repo) and render from the repo root:

```bash
curl -fO https://repo1.maven.org/maven2/net/sourceforge/plantuml/plantuml/1.2026.9/plantuml-1.2026.9.jar
java -jar plantuml-1.2026.9.jar -tpng docs/diagrams/component.puml docs/diagrams/deployment-local.puml docs/diagrams/deployment-aws.puml
```

It needs Java (any recent version; we used 25) and Graphviz `dot` (`sudo apt install graphviz`). The PNG is written next to the `.puml`. IntelliJ's PlantUML plugin shows the same picture while editing.

### Notation choices

| Choice | Why |
|---|---|
| PlantUML, not Mermaid | Mermaid has no UML component or deployment diagram; PlantUML draws real UML (lollipop interfaces, 3D nodes, artifacts) |
| Provided interface = lollipop on the module that owns the `api` package; required = dashed `..>` arrow to the interface | Standard UML; one arrow per real dependency, so the picture can be checked against the grep above |
| `AssetDamagedEvent` as an interface with `<<event>>` | It is the "contract" between incident and reassignment, but nobody calls it: incident publishes it, reassignment's `LocalAssetDamagedListener` receives it (`@TransactionalEventListener(AFTER_COMMIT)`, `@Async`) |
| `common.error`, `common.paging`, `CurrentUser`, `Clock` not drawn as arrows | They are used by nearly every module; ~20 extra arrows would hide the interesting ones. The legend lists who uses them |
| One `JDBC` arrow from the whole application to PostgreSQL | Each module reaches its own tables through Spring Data JPA; drawing six identical arrows adds nothing |
| Deployment: `<<device>>` for machines, `<<executionEnvironment>>` / `<<container>>` for JVM, Docker, Node, PostgreSQL, artifacts for deployed files | UML 2 deployment notation; the professor can map every box to a real thing |
| AWS network parts (account, region, VPC, subnets) as plain rectangles | They are not nodes in UML; rectangles group the nodes like the draw.io picture does |
| AWS diagram titled "PLANNED (W7–W9), not deployed yet" | Nothing runs on AWS yet; Terraform is still to come |

### Agreement with `aws-architecture.drawio`

Same pieces and paths as the draw.io picture (CloudFront → EC2 :8080 → RDS :5432, S3, SES, SSM, ECR, CloudWatch, GitHub Actions → ECR + SSM). The UML version adds what draw.io leaves out: the IAM instance role, the env file written from `/campus/*`, the Docker/JRE layers inside EC2 and the ports for the AWS API calls. No contradiction with ADR-002/003/005 was found.

### Tests

| Check | Result |
|---|---|
| `java -jar plantuml.jar -tpng` on all three files | Rendered without errors (PlantUML 1.2026.9, Graphviz `/usr/bin/dot`) |
| Every PNG opened and checked by eye | No overlapping boxes; every arrow and label readable |

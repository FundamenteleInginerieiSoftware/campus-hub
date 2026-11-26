# Diagrams

Text sources live next to their exported PNGs. PlantUML (`.puml`) and Mermaid (`.mmd`) for UML, draw.io only for the AWS picture (AGENTS.md §6). After changing a `.puml`, re-render it from the repo root with `java -jar plantuml.jar -tpng docs/diagrams/<file>.puml` (see `documentation/Necula_Matei/32-component-deployment-diagrams.md`).

| File | What it shows | Owner |
|---|---|---|
| `component.puml` + `.png` | UML component diagram: backend modules, their `api` interfaces, the dependencies between them (from the real imports), Observer and Strategy | Matei |
| `deployment-local.puml` + `.png` | UML deployment diagram: a developer laptop (browser, Vite :5173, Spring Boot :8080, PostgreSQL 18 :5432) and the GitHub Actions `ci` job | Matei |
| `deployment-aws.puml` + `.png` | UML deployment diagram of the planned AWS production setup (CloudFront, EC2 + Docker, RDS, S3, SES, SSM, ECR). Terraform parts 1 + 2 are applied (network, photo bucket, ECR, instance role, SSM); EC2 and CloudFront are still to come | Matei |
| `aws-architecture.drawio` + `.png` | The same AWS setup with AWS icons (ADR-002) | Matei |
| `reservation-lifecycle.mmd` | State diagram of a reservation (`CONFIRMED`, `CHECKED_OUT`, `OVERDUE`, ...) | Calin |
| `reservation-booking-sequence.mmd` | Sequence diagram: booking flow with pessimistic row lock, quota check, and overlap validation | Calin |
| `reassignment-sequence.mmd` | Sequence diagram: incident report → `AssetDamagedEvent` → reassignment engine | Calin |
| `asset-lifecycle.mmd` | State diagram of an equipment asset (`AVAILABLE`, `UNDER_MAINTENANCE`, `RETIRED`) | Liviu |
| `catalog-and-common-class.mmd` | UML class diagram of `catalog`, `common` (`error`, `paging`, `audit`), `storage`, and `notification` | Liviu |
| `database-er.mmd` | Entity-Relationship (ER) diagram of all 8 PostgreSQL 18 tables from Flyway migrations | Liviu |

## Progress & team overview visuals (SVG)

These standalone SVGs are embedded in [`PROGRESS.md`](../../PROGRESS.md) as a quick visual snapshot for teammates and the professor.

| File | What it shows | Owner |
|---|---|---|
| `progress-system-overview.svg` | End-to-end system sketch: browser SPA, Spring Boot 4.1 monolith, PostgreSQL 18 / S3 / SES, and the Observer/Strategy reassignment loop | Matei |
| `progress-module-ownership.svg` | Module ownership map and public `api` contracts across all four team members | Matei |
| `progress-work-profile.svg` | Team contribution profile by work type and cross-team peer review collaboration | Matei |
| `progress-codebase-loc.svg` | Lines of code by explicit authored category (backend tests, backend Java, frontend, infra/CI, DB/config, personal & shared docs) with exclusions | Matei |
| `progress-testing-pyramid.svg` | Testing pyramid across all 5 test layers (`1,221` tests in `113` files) and PostgreSQL concurrency/integrity guardrails | Matei |
| `progress-engineering-health.svg` | Engineering process and review health across `241` post-reset PRs (median size, turnaround time, issue closure, and PR type mix) | Matei |
| `progress-domain-map.svg` | Database schema map of all `12` PostgreSQL 18 tables grouped by module owner with cross-module foreign keys | Matei |

# Diagrams

Text sources live next to their exported PNGs. PlantUML (`.puml`) and Mermaid (`.mmd`) for UML, draw.io only for the AWS picture (AGENTS.md §6). After changing a `.puml`, re-render it from the repo root with `java -jar plantuml.jar -tpng docs/diagrams/<file>.puml` (see `documentation/Necula_Matei/32-component-deployment-diagrams.md`).

| File | What it shows | Owner |
|---|---|---|
| `component.puml` + `.png` | UML component diagram: backend modules, their `api` interfaces, the dependencies between them (from the real imports), Observer and Strategy | Matei |
| `deployment-local.puml` + `.png` | UML deployment diagram: a developer laptop (browser, Vite :5173, Spring Boot :8080, PostgreSQL 18 :5432) and the GitHub Actions `ci` job | Matei |
| `deployment-aws.puml` + `.png` | UML deployment diagram of the planned AWS production setup (CloudFront, EC2 + Docker, RDS, S3, SES, SSM, ECR) | Matei |
| `aws-architecture.drawio` + `.png` | The same AWS setup with AWS icons (ADR-002) | Matei |
| `reservation-lifecycle.mmd` | State diagram of a reservation (`CONFIRMED`, `CHECKED_OUT`, `OVERDUE`, ...) | Calin |
| `reassignment-sequence.mmd` | Sequence diagram: incident report → `AssetDamagedEvent` → reassignment engine | Calin |

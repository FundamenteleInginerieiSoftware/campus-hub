# AWS Cloud Architecture & CI/CD Delivery (Matei Necula)

**Author:** Matei Necula (Member 1: cloud, security, auth) · **State of the code:** `main` at `545e334` (28 Nov 2026)  
**Scope:** AWS architecture decisions (ADR-001 – ADR-005), 3-stage Terraform IaC (`infra/`), VPC/SG/IMDSv2/SSM security, multi-stage single-origin Docker packaging (`Dockerfile`, `SpaConfig`), production AWS adapters (`S3StorageService`, `SesNotificationService`), CI/CD pipelines (`ci.yml`, `docs-check.yml`, `deploy.yml`, `sync-wiki.yml`), cost control, and operational practices.

Everything below was verified directly against `infra/`, `.github/workflows/`, `Dockerfile`, and `backend/` on `main` (`545e334`). Security controls (JWT, RBAC, IDOR, threat model) are covered in [`docs/report/security.md`](security.md); copy-pasteable day-to-day commands live in [`docs/runbook.md`](../runbook.md).

---

## 1. Architecture Decision Records (ADR-001 – ADR-005)

Before provisioning any AWS resource, five architectural decisions were recorded in [`docs/adr/`](../adr/) to balance realistic cloud engineering against a 12-week course schedule and $100 of AWS Free-plan credits:

| ADR | Decision | Key Trade-Off Accepted | Link |
|---|---|---|---|
| **ADR-001** | **Local-first development:** PostgreSQL 18 on every laptop and in CI; AWS used only for deploys and demos; dev/prod adapters switched by `@Profile("!prod")` vs `@Profile("prod")` | Cloud-only quirks (CloudFront headers, IMDSv2 hop limit) surface at first deploy, so W7–W8 was scheduled early | [`001-local-first.md`](../adr/001-local-first.md) |
| **ADR-002** | **Single EC2 `t3.small` (Docker) behind CloudFront + private RDS PG 18:** React build served from Spring Boot (one origin, no CORS); no ECS, ALB, or NAT gateway | ~30 s deploy downtime and single-server availability; plain HTTP on CloudFront → EC2 hop (~$30–35/mo vs ~$60–70/mo for ECS + ALB) | [`002-single-ec2-behind-cloudfront.md`](../adr/002-single-ec2-behind-cloudfront.md) |
| **ADR-003** | **Secrets in SSM Parameter Store (`SecureString`):** `/campus/*` encrypted with AWS-managed KMS, rendered to root-only `/etc/campus.env` (`umask 077`), no Spring Cloud AWS dependency | Free vs $0.40/secret/mo in Secrets Manager; manual rotation instead of automatic DB password rotation | [`003-ssm-parameter-store-for-secrets.md`](../adr/003-ssm-parameter-store-for-secrets.md) |
| **ADR-004** | **Spring `AFTER_COMMIT` events by default, SQS as stretch goal:** switched by `app.messaging.sqs.enabled` (default `false`); idempotent handlers | Millisecond crash window after DB commit vs zero extra queues/DLQs for a single-process deployment | [`004-spring-events-now-sqs-later.md`](../adr/004-spring-events-now-sqs-later.md) |
| **ADR-005** | **AWS Free plan without Organizations / Identity Center:** root MFA with no keys, `eu-central-1` (Frankfurt), monthly budget alerts ($5 / $20 / $40), GitHub OIDC for CI deploys | Keeps the $100 credits (until 17 Mar 2027); daily Terraform runs use a laptop-only IAM key (`terraform-admin`) | [`005-aws-account-free-plan.md`](../adr/005-aws-account-free-plan.md) |

---

## 2. Infrastructure as Code (`infra/` — 3-Stage Terraform Delivery)

All AWS resources are managed declaratively with **Terraform `>= 1.10`** (`hashicorp/aws ~> 6.72`, `hashicorp/random ~> 3.9`, locked via `.terraform.lock.hcl`) and tagged automatically with `Project = "campus-hub"` and `ManagedBy = "terraform"` (`infra/versions.tf`) for Cost Explorer visibility.

Remote state lives in an encrypted, versioned, private S3 bucket (`campus-hub-tfstate-099cd54b`) bootstrapped once by `infra/bootstrap/main.tf` (protected with `prevent_destroy = true`). Locking uses Terraform 1.10's native S3 lockfile (`use_lockfile = true` in `infra/backend.tf`), eliminating the need for a DynamoDB lock table.

The infrastructure was delivered and applied incrementally across five focused PRs:

| Stage / PR | Files | Resources Provisioned | Applied / Live Proof |
|---|---|---|---|
| **Part 1 — Network & Firewall** (`#249`) | `bootstrap/main.tf`, `versions.tf`, `backend.tf`, `variables.tf`, `network.tf`, `security_groups.tf`, `outputs.tf` | S3 state bucket, VPC `10.0.0.0/16` (DNS support + hostnames), IGW, 1 public subnet (`10.0.1.0/24`), 2 private DB subnets in 2 AZs (`10.0.11.0/24`, `10.0.12.0/24`, local-only route table), chained SGs `campus-hub-app` and `campus-hub-db` | Applied in W6 |
| **Part 2 — Data, Storage, IAM & SSM** (`#250`) | `rds.tf`, `storage.tf`, `iam.tf`, `ssm.tf` | Private SSE-AES256 S3 bucket `campus-hub-photos-<hex>` (all 4 `PublicAccessBlock` flags on), immutable `scan_on_push` ECR repo `campus-hub` (lifecycle keep 10), CloudWatch log group `/campus-hub/app` (7 days), EC2 role + instance profile `campus-hub-app`, SSM `/campus/*` parameters (`random_password` for DB + JWT), RDS definition gated by `db_enabled` | Applied in W6 (`db_enabled = false`); first ECR push 9 Nov |
| **Part 3 — Compute, CloudFront, RDS & SES** (`#304`) | `compute.tf`, `user-data.sh.tftpl`, `cdn.tf`, `ses.tf`, `terraform.tfvars.example` (+ `ssm.tf`, `variables.tf`, `outputs.tf`) | Amazon Linux 2023 x86 EC2 `t3.small` + Elastic IP, first-boot `/usr/local/bin/campus-deploy`, CloudFront `PriceClass_100` distribution, RDS PostgreSQL 18 `db.t4g.micro` (`db_enabled = true`), SES sender identity, `/campus/MAIL_FROM`, `/campus/ADMIN_EMAIL`, `/campus/ADMIN_PASSWORD` | Applied **17 Nov 2026** (11 added); app live on CloudFront, Flyway ran all migrations on RDS PG 18.3 over SSL |
| **RDS Auto-Start Alert** (`#309`) | `alerts.tf` (+ `variables.tf`, `terraform.tfvars.example`) | SNS topic `campus-hub-alerts` (policy restricted by `AWS:SourceOwner` + `aws:SourceAccount` for `events.rds.amazonaws.com`), email subscription, RDS event subscription `campus-hub-rds` (`notification` category for `RDS-EVENT-0154`) | Applied **17 Nov 2026** (4 added); protects paused RDS against silent 7-day auto-restart billing |
| **GitHub OIDC Deploy Role** (`#320`) | `github-deploy.tf` (+ `outputs.tf`) | `aws_iam_openid_connect_provider.github`, role `campus-hub-github-deploy` restricted to immutable `main` subject, least-privilege inline policy (ECR push, EC2 describe, SSM `SendCommand` on our instance only) | Applied & proved live **22 Nov 2026** |

Diagram references: UML deployment diagram [`docs/diagrams/deployment-aws.puml`](../diagrams/deployment-aws.puml) and AWS icon diagram [`docs/diagrams/aws-architecture.drawio`](../diagrams/aws-architecture.drawio).

---

## 3. Cloud Network, Security & Secrets Design

### Network & Firewall Chain

```
Browser --(HTTPS :443)--> CloudFront --(HTTP :8080, AWS prefix list)--> EC2 t3.small --(JDBC/SSL :5432, SG ref)--> RDS PostgreSQL 18
```

| Control | Implementation | Where |
|---|---|---|
| **No direct internet access to EC2** | `campus-hub-app` ingress allows TCP `8080` **only** from AWS managed prefix list `com.amazonaws.global.cloudfront.origin-facing` (55 of 60 SG rule slots). No port 22 SSH rule exists; operator shells use **SSM Session Manager**. | `infra/security_groups.tf` |
| **Zero-egress private database** | RDS (`publicly_accessible = false`) sits in 2 private subnets with no internet route. `campus-hub-db` allows TCP `5432` **only** by security-group reference from `campus-hub-app` (survives EC2 IP changes) and defines **zero** egress rules. | `infra/network.tf`, `infra/security_groups.tf`, `infra/rds.tf` |
| **Compute & metadata hardening** | `http_tokens = "required"` enforces IMDSv2 (blocks SSRF against `169.254.169.254`). `http_put_response_hop_limit = 2` lets the AWS SDK inside the Docker container reach instance credentials one network hop behind the host. Root 20 GB gp3 EBS volume is encrypted; `cpu_credits = "standard"` avoids surprise burst charges; `lifecycle { ignore_changes = [ami] }` prevents unintended instance replacement when AWS publishes new AL2023 AMIs. | `infra/compute.tf` |
| **CloudFront pass-through & headers** | Custom cache policy `campus-hub-pass-through` whitelists `Authorization` in the cache key with `min_ttl = 0`, `default_ttl = 0`, `max_ttl = 1` (preventing CloudFront from stripping our JWT while Spring Security's `Cache-Control: no-cache, no-store` prevents actual caching). `Managed-AllViewerExceptHostHeader` forwards `Content-Type` and `Idempotency-Key`; `Managed-SecurityHeadersPolicy` adds HSTS (`max-age=31536000`) and `nosniff`; `custom_error_response` sets `error_caching_min_ttl = 0` on 5xx so deploys recover instantly. | `infra/cdn.tf` |
| **SSE under CloudFront 30 s timeout** | `origin_read_timeout = 30` seconds. Liviu's `LiveStreams` flushes `:connected` immediately and sends `:hb` every 20 s (`#317`), backed by Matei's dedicated 3-thread `campus-sched-` pool (`#347`) so Calin's scheduled sweeps cannot delay heartbeats past 30 s. | `infra/cdn.tf`, `application.yml` |

### Least-Privilege EC2 Instance Role (`infra/iam.tf`)

No static AWS access keys exist on the server, in the image, or in GitHub. The EC2 instance profile `campus-hub-app` attaches `AmazonSSMManagedInstanceCore`, `AmazonEC2ContainerRegistryReadOnly`, and one scoped inline policy:

| Statement (`Sid`) | Actions | Resource Scope | Why |
|---|---|---|---|
| `PhotoObjects` | `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` | `${aws_s3_bucket.photos.arn}/*` | Incident photo upload, download, and orphan cleanup (`S3StorageService`, `#129`) |
| `PhotoList` | `s3:ListBucket` | `${aws_s3_bucket.photos.arn}` | Ensures S3 answers `404 NoSuchKey` instead of `403 AccessDenied` for missing keys (`#177`) |
| `SendEmail` | `ses:SendEmail` | `*` | Transactional notifications (`SesNotificationService`, `#129`; restricted by SES sandbox) |
| `ReadAppParameters` | `ssm:GetParameter`, `ssm:GetParameters`, `ssm:GetParametersByPath` | `.../parameter/campus` and `.../parameter/campus/*` | `campus-deploy` materializes `/etc/campus.env`; AWS-managed `aws/ssm` KMS decrypts `SecureString` automatically |
| `WriteLogs` | `logs:CreateLogStream`, `logs:PutLogEvents` | `${aws_cloudwatch_log_group.app.arn}:*` | Docker `awslogs` driver ships container stdout/stderr to `/campus-hub/app` |

### SSM Parameter Store Secrets (`infra/ssm.tf`)

Every parameter under `/campus/` matches the exact environment variable name in `backend/src/main/resources/application-prod.yml`, so `/usr/local/bin/campus-deploy` (`infra/user-data.sh.tftpl`) renders `/etc/campus.env` under `umask 077` (mode `0600`, root-only) with zero mapping boilerplate:

| Parameter | Type | Source / Rule |
|---|---|---|
| `/campus/DB_PASSWORD` | `SecureString` | 32-char `random_password.db` (`special = false` for RDS compatibility) |
| `/campus/JWT_SECRET` | `SecureString` | 64-char `random_password.jwt` (64 UTF-8 bytes, well above HS256's 32-byte minimum) |
| `/campus/ADMIN_PASSWORD` | `SecureString` | 24-char `random_password.admin` (within `AdminBootstrap`'s 8–72 byte check) |
| `/campus/DB_URL` | `String` | `jdbc:postgresql://<rds-endpoint>/campus?sslmode=require` (enforces TLS; `depends_on` prevents EC2 booting before RDS is ready) |
| `/campus/DB_USERNAME`, `AWS_REGION`, `PHOTO_BUCKET`, `MAIL_FROM`, `ADMIN_EMAIL` | `String` | Derived from Terraform resources and gitignored `terraform.tfvars` |

---

## 4. Containerization, Single-Origin Serving & Prod Adapters

### 3-Stage `Dockerfile` (`#114`, `#252`)

| Stage | Base Image | Purpose |
|---|---|---|
| **1. `frontend`** | `node:24-slim` | Runs `npm ci && npm run build` to produce `frontend/dist` (Debian `slim` glibc rather than Alpine musl so Vite's native binaries work cleanly). |
| **2. `backend`** | `eclipse-temurin:25-jdk` | Caches Gradle dependencies, copies `frontend/dist` into `src/main/resources/static`, runs `./gradlew bootJar --no-daemon` (tests already ran in `ci.yml`). |
| **3. Runtime** | `eclipse-temurin:25-jre-alpine` | Runs `apk upgrade --no-cache`, creates non-root `app` user (`USER app`), sets `SPRING_PROFILES_ACTIVE=prod`, `-XX:MaxRAMPercentage=75`, and a Docker `HEALTHCHECK` against `http://localhost:8080/actuator/health`. |

**Real CVE caught by ECR scan (`#252`):** When the first image was pushed to ECR on 9 Nov 2026, `scan_on_push` flagged a critical vulnerability (`CVE-2026-4738`) in the base image's `zlib 1.3.2`. Adding `apk upgrade --no-cache` to Stage 3 patched the OS packages at build time, bringing the 17 Nov deploy image (`cffc1c3`) to **0 ECR scan findings**.

### Single-Origin Routing & Production Runtime Hardening

- **No CORS needed:** `SpaConfig` (`#114`) serves static files from `classpath:/static/` and falls back to `index.html` for React client routes (while leaving `/api/**` and file-extension misses as proper 401/404 responses).
- **CloudFront edge fixes (`#308`):** Live testing on 17 Nov revealed two edge bugs fixed immediately in `#308`: `OpenApiConfig` now sets `@Server(url = "/")` so Swagger "Try it out" uses the HTTPS CloudFront origin instead of EC2's HTTP hostname, and `SecurityConfig` permits `HEAD /**` for uptime/CDN probes.
- **Container memory & disk hygiene (`#304`, `#366`):** `campus-deploy` runs the container with `--memory 1536m` (leaving ~512 MB of the 2 GB `t3.small` for OS, Docker, and SSM Agent; JVM heap scales via `-XX:MaxRAMPercentage=75`) and executes `docker image prune -f || true` after startup so repeated deploys never exhaust the 20 GB EBS volume.
- **Prod adapters & thread-pool isolation (`#129`, `#177`, `#332`, `#347`, `#366`):** `AwsConfig` configures `S3Client` and `SesV2Client` with 10 s per-attempt / 20 s total timeouts. `SesNotificationService` pushes live notifications to `LiveStreams` *before* calling SES (truncated to whole seconds, `#332`/`#366`) and runs on its own prod-only 8-thread `campus-ses-` pool (`AsyncConfig.MAIL_EXECUTOR`, `#366`), keeping slow mail calls isolated from the 4-thread `campus-async-` pool (reassignment + live SSE) and the 3-thread `campus-sched-` pool (sweeps + 20 s SSE heartbeat, `#347`).

---

## 5. CI/CD Workflows & Branch Governance (`.github/workflows/`)

Branch protection ruleset `protect-main` enforces PR-only merges to `main`, 1 approval from any teammate, stale-approval dismissal, resolved review conversations, up-to-date branch, squash-only merges, and two required status checks (`ci` and `docs-check`).

| Workflow | Owner | Trigger | What It Guarantees |
|---|---|---|---|
| **`ci.yml`** *(required)* | Stefania | `pull_request`, `push` to `main` | Spins up a real `postgres:18` service container (`campus_test`), runs `./gradlew build` (all unit/slice/IT/concurrency/ArchUnit tests + Spotless + JaCoCo artifact) on Temurin Java 25, then runs `npm ci`, `npm run lint`, and `npm run build` on Node 24. No `hashFiles` skip guards (`#303`). |
| **`docs-check.yml`** *(required since 10 Nov)* | Matei (`#277`, `#279`) | `pull_request` (incl. `labeled`/`unlabeled`) | Diffs `base...head`; whenever `backend/src/main/` or `frontend/src/` changes, verifies the PR also updates `documentation/<Member>/`, `docs/whats-left.md`, and `AGENTS.md` §7 (skippable only via `no-docs` label or Dependabot). |
| **`deploy.yml`** | Matei (`#320`, proved 22 Nov) | `workflow_run` after green `ci` on `push` to `main`, or manual `workflow_dispatch` (optional `sha`) | Verifies commit is an ancestor of `origin/main` (skips superseded automatic runs), authenticates to AWS via **GitHub OIDC** (`id-token: write`, no static keys), skips with `::notice::` when EC2 is paused, builds/pushes immutable `campus-hub:<sha>` to ECR (reusing existing tag on re-run/rollback), invokes `/usr/local/bin/campus-deploy <sha>` via SSM Run Command (10-min poll loop), and polls `$APP_URL/actuator/health` through CloudFront up to 3 min for `"status":"UP"`. Serialized by `concurrency: deploy` (`cancel-in-progress: false`). |
| **`sync-wiki.yml`** | Matei (`#358`, `#366`) | `push` to `main` touching `PROGRESS.md` or `docs/diagrams/progress-*.svg`, or `workflow_dispatch` | Copies `PROGRESS.md` to the `.wiki` repo's `Home.md`, rewrites relative image links (`raw.githubusercontent.com`) and repo links (`blob/main` / `tree/main`), and pushes only when content changes. |

### Why GitHub OIDC (`infra/github-deploy.tf`) Is Hardened Against Repo Takeover

The trust policy on `campus-hub-github-deploy` checks `aud = "sts.amazonaws.com"` and an exact `StringEquals` match on GitHub's **immutable subject** claim (`repo:FundamenteleInginerieiSoftware@338709262/campus-hub@1407699804:ref:refs/heads/main`). Pull requests, forks, non-`main` branches, and any recreated repository with the same name are rejected by AWS STS before credentials are issued. The role itself has zero read access to `/campus/*` SSM secrets, S3 photos, or RDS, and its `ssm:SendCommand` permission is scoped strictly to `AWS-RunShellScript` and the single EC2 instance ARN.

---

## 6. Live Verification & Cost Control

### Live Deployment Proof

- **17 Nov 2026 (`#304`):** `terraform apply` added 11 resources; cloud-init booted `campus-hub:cffc1c3` (0 ECR findings), Flyway applied all migrations on RDS PG 18.3 over SSL, `AdminBootstrap` created the first admin, CloudFront returned HTTPS 200 UP with HSTS and `Cache-Control: no-cache, no-store`, and `Authorization` passed through to `GET /api/v1/users/me` and admin endpoints. Live checks caught the Swagger server URL and `HEAD /` bugs (fixed in `#308`).
- **22 Nov 2026 (`#320`):** Applied `infra/github-deploy.tf`, configured repo variables `AWS_DEPLOY_ROLE_ARN` and `APP_URL`, and proved `deploy.yml` end-to-end via OIDC (and confirmed clean skip behavior when AWS is paused).

### Cost Profile & Guardrails

| Operating State | Rough Cost | What Bills | Controls in Place |
|---|---:|---|---|
| **Running 24/7** | ~$1.20/day (~$30–35/mo) | EC2 `t3.small` (~$17.5/mo), RDS `db.t4g.micro` (~$16/mo), Elastic IP (~$3.6/mo); CloudFront/S3/SES/SSM/ECR are pennies | Budget `campus-hub-monthly` ($5 / $20 / $40 email alerts); `t3` `cpu_credits = "standard"`; 7-day CloudWatch retention; ECR lifecycle keeping 10 images |
| **Paused between demos** | ~$0.25/day | Elastic IP + 20 GB EBS + 20 GB RDS storage | Stop EC2 then RDS (`docs/runbook.md` §2); SNS alert (`campus-hub-alerts`, `#309`) emails immediately if AWS auto-restarts RDS after 7 days (`RDS-EVENT-0154`) |
| **Post-grading (`terraform destroy`)** | $0.00/day | Only tiny versioned Terraform state bucket (optional final cleanup) | `skip_final_snapshot = true`, `force_destroy = true` on photos bucket, `force_delete = true` on ECR |

---

## 7. Known Limitations & Future Work

| Current Trade-Off / Planned Follow-Up | Production / Next Step |
|---|---|
| Single EC2 instance (~30 s downtime per deploy) and single-AZ RDS | ECS Fargate (2+ tasks) behind an ALB + Multi-AZ RDS (documented in ADR-002). |
| CloudFront → EC2 uses plain HTTP (and SG permits any CloudFront distribution) | Custom domain + ACM TLS certificate on the origin + secret `X-Origin-Verify` header. |
| CloudWatch metric alarms (EC2 CPU/status, RDS storage/CPU) and graceful container drain are not merged on `main` yet | Planned / in-flight follow-up (`infra/` alarms & graceful Spring shutdown) building on the existing `campus-hub-alerts` SNS topic (`#309`). |
| SQS reassignment bridge (`app.messaging.sqs.enabled=true`) remains a stretch goal | Only if time permits after M2 (`ADR-004`); `AssetDamagedEvent` is already JSON-serializable. |
| Hard-coded `campus-hub` names and `/campus/` SSM prefix in `infra/*.tf` | **Future Work:** parameterize `infra/variables.tf` (`project_name`, `ssm_prefix`, `github_repo`, `github_oidc_sub`) after the `v1.0.0` defense as detailed in the **[Reusable Template Blueprint](../reusable-template-blueprint.md)**. |

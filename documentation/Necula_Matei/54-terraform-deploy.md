# Terraform part 3: the first AWS deploy (server, CloudFront, database on, e-mail sender) (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-17
**Branch / PR:** `infra/w7-ec2-cloudfront` (#304)
**See also:** `44-terraform-network.md`, `45-terraform-data.md`, `46-docker-os-patches.md`, `docs/adr/002-single-ec2-behind-cloudfront.md`, `docs/adr/003-*`

---

## Part 1: In simple terms

1. **What was missing:** after parts 1 and 2 AWS had the network, the database (written but switched off), the photo bucket, the image registry and the secrets. There was no computer to run the app and no public address for students.
2. **The server:** one small virtual computer (EC2). On its first start it installs Docker, downloads our newest app image and starts it. It has a fixed address (Elastic IP), so the front door never has to be re-pointed.
3. **The front door:** CloudFront gives the app a secure `https://....cloudfront.net` address with a free certificate. The server only accepts visitors that come through it.
4. **The database is switched on** by default now (it costs money every hour).
5. **E-mail and first admin:** Terraform registers the sender address for e-mails (AWS sends a verification link to it) and stores the first admin's login. The admin password is random; only Matei reads it with one command.
6. **Cost:** about $30 to $35 a month for everything (database ~$16, server ~$17.5, the fixed address ~$3.6). The $100 AWS credits cover it.

**Result:** the infrastructure for the first live deploy was written, checked (`validate`, `plan`), applied on 17 Nov 2026 (11 resources added) and tested live (status below).

| Status | |
|---|---|
| Written, `terraform validate` + `terraform plan` | done |
| `terraform apply` (17 Nov 2026) | **done, 11 resources added** |
| Live checks: HTTPS, health, admin login | **done** (table in Part 2); a first booking on AWS is not tried yet |

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `infra/compute.tf` (new) | AMI lookup, `aws_instance.app` (t3.small), `aws_eip.app` |
| `infra/user-data.sh.tftpl` (new) | First-boot script: Docker + `/usr/local/bin/campus-deploy` |
| `infra/cdn.tf` (new) | Cache policy, `aws_cloudfront_distribution.app` |
| `infra/ses.tf` (new) | `aws_sesv2_email_identity.sender` |
| `infra/terraform.tfvars.example` (new) | Template for `terraform.tfvars` (gitignored via `*.tfvars`) |
| `infra/ssm.tf` | `random_password.admin` + `/campus/ADMIN_PASSWORD` (SecureString), `/campus/ADMIN_EMAIL`, `/campus/MAIL_FROM` |
| `infra/variables.tf` | `db_enabled` default `true`; new `mail_from`, `admin_email` (no default) |
| `infra/outputs.tf` | `app_url`, `app_instance_id`, `admin_password` (sensitive) |
| `infra/rds.tf` | Comment only (default flipped) |

### Key decisions

| Decision | Why |
|---|---|
| `t3.small` (x86), not `t4g.small` (ARM) | The ECR image is `linux/amd64` (checked on AWS on 17 Nov), built on x86 laptops and CI. An ARM server would need QEMU cross-builds of Gradle + Node on every deploy. t3.small costs ~$3/month more (~$17.5 vs ~$14). The Dockerfile is already tuned for it (`MaxRAMPercentage=75`, 2 GB). AMI is Amazon Linux 2023 x86_64; `ignore_changes = [ami]` so a new AMI release does not replace the server |
| Elastic IP | CloudFront's origin host name (`aws_eip.app.public_dns`) never changes, even after a stop/start |
| IMDSv2 required, hop limit 2 | `http_tokens = "required"` blocks the SSRF trick against `169.254.169.254`. Hop limit 2 because the app runs in a container one hop behind the host; with 1 the AWS SDK in `S3StorageService` and `SesNotificationService` gets no credentials |
| User data runs once | Installs Docker, writes `campus-deploy`. The script pulls the newest ECR image (or the tag given as argument), turns every `/campus/*` SSM parameter into `/etc/campus.env` (root-only, `umask 077`; ADR-003: names equal the variables `application-prod.yml` reads) and runs the container with the `awslogs` driver. W8 `deploy.yml` reuses it through SSM Run Command: `campus-deploy <commit-sha>`. `user_data_replace_on_change = true`: a changed script means a new server |
| CloudFront cache policy | `Authorization` is in the cache key, TTL max 1 s. Bug avoided: CloudFront strips `Authorization` (our JWT) unless it is in the key, and a key needs caching on. Spring Security already sends `Cache-Control: no-store`, so nothing is really cached, and the key keeps one user's answer from reaching another |
| Origin request policy | `Managed-AllViewerExceptHostHeader`: passes `Idempotency-Key`, `Content-Type`, query strings; Spring sees the server's own host |
| Other CloudFront settings | `PriceClass_100`, `redirect-to-https`, all methods allowed, HTTP/2+3. Known limit: CloudFront to EC2:8080 is plain HTTP (stated in `docs/report/security.md`) |
| `db_enabled = true` | Creates RDS. Turning it off deletes the data, see `45-terraform-data.md` |
| SSM `MAIL_FROM`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` | The app does not start without `MAIL_FROM`; `AdminBootstrap` (#88) creates the first admin from the other two (prod profile only). Password: random 24 letters/digits, inside the 8-72 byte limit `AdminBootstrap` checks. Read with `terraform output -raw admin_password` |
| SES sender identity in Terraform | `SesNotificationService` (#129) needs a verified sender. AWS e-mails a link to `mail_from`; someone must click it. On 17 Nov no SES identity existed in any region. In the SES sandbox, mail only goes to verified recipients (W9) |
| Values in `terraform.tfvars` | `mail_from` and `admin_email` have no default; the file is gitignored, the example is committed |

### How it connects to teammates' work

| Teammate / module | Link |
|---|---|
| Stefania: frontend, `Dockerfile` build stage | The React build is served by Spring Boot from the same origin, so CloudFront needs one behavior and the API needs no CORS |
| Calin: `reservation/` | `Idempotency-Key` on `POST /reservations` must reach Spring: the origin request policy forwards it. Times on AWS: the container runs in UTC, Calin's `Clock` uses Europe/Bucharest |
| Liviu: `LocalStorageService` / `LoggingNotificationService` | On AWS the `prod` profile swaps in Matei's `S3StorageService` / `SesNotificationService` (needs the instance role and IMDS hop limit 2) |
| Calin/Matei: local vs. SQS trigger | `app.messaging.sqs.enabled` stays false; the local listener is used |

### Tested

| Command | Result |
|---|---|
| `terraform validate` (in `infra/`) | Success |
| `terraform plan` | 11 to add, 0 to change, 0 to destroy |
| `terraform apply` (17 Nov 2026) | 11 added |
| CloudFront | Deployed; HTTP to HTTPS answers 301 |
| Server boot (checked through SSM) | cloud-init finished; container from image `cffc1c3` running; `/etc/campus.env` mode 600 with the 9 expected names |
| App log | Started with profile `prod` in about 133 s (slow first start on `t3.small`; the Docker HEALTHCHECK start-period of 60 s plus 3 retries still passed, health UP). Flyway applied all 15 migrations on RDS PostgreSQL 18.3 over SSL. `AdminBootstrap` created the admin. No ERROR lines |
| `/actuator/health` through CloudFront | 200 UP |
| Response headers | HSTS `max-age=31536000`, `Cache-Control: no-cache, no-store`, `X-Cache: Miss`, `nosniff` |
| Admin login through CloudFront | 200 with a token; `GET /api/v1/users/me` 200 with role ADMIN (so the `Authorization` header passes CloudFront); an admin endpoint 200 with the token, 401 without |
| SES sender identity | Verified (link clicked) |
| ECR image scan | 0 findings |

The address is the `*.cloudfront.net` one from `terraform output app_url` (not written in the repo).

### Cost (monthly, covered by the $100 credits)

| Item | ~USD |
|---|---|
| RDS `db.t4g.micro` | 16 |
| EC2 `t3.small` | 17.5 |
| Public IPv4 (Elastic IP) | 3.6 |
| **Total** | **30 to 35** |

### Problems and fixes

| Problem | Fix |
|---|---|
| CloudFront drops the `Authorization` header, every API call would be 401 | Cache policy with `Authorization` in the key and max 1 s TTL (`cdn.tf` comment) |
| Container could not get AWS credentials (IMDS hop limit 1) | `http_put_response_hop_limit = 2` |
| The next apply after a new Amazon Linux release would replace the server | `lifecycle { ignore_changes = [ami] }` |
| `${...}` in the shell script clashes with `templatefile()` | Doubled dollar sign for bash variables in `user-data.sh.tftpl` |
| EC2 booted in parallel with RDS: the env file had no `DB_URL` and the container restarted in a loop | `depends_on` on the 4 SSM parameters in `compute.tf`, plus a check in `campus-deploy` that the required variables exist |
| The ECR query could print `None` (empty repo or untagged image) | `--filter tagStatus=TAGGED` and an explicit check |
| `dnf upgrade` on Amazon Linux 2023 installs nothing (release pinned to the AMI) | Removed; OS updates with `dnf upgrade --releasever=latest` |
| No container memory limit: `MaxRAMPercentage=75` meant 75% of the whole 2 GB host | `--memory 1536m` on `docker run` |
| t3 default "unlimited" CPU credits are billed | `cpu_credits = "standard"` |
| No HSTS: Spring only sends it over HTTPS and sees plain HTTP from CloudFront | CloudFront response headers policy Managed-SecurityHeadersPolicy (`cdn.tf`) |
| CloudFront caches 5xx for 10 s (e.g. during a redeploy) | `error_caching_min_ttl = 0` |

### Known follow-ups

| Item | Note |
|---|---|
| Swagger "Try it out" | Confirmed broken: `/v3/api-docs` `servers` is the EC2 host over http. Fixed in #308 (`@Server(url = "/")` in `OpenApiConfig`, doc 55) |
| `HEAD /` | Answers 401 while `GET /` is 200 (`SecurityConfig` permits only GET for the static pages). Fixed in #308 (`HEAD /**` rule, doc 55) |
| Swagger in prod | springdoc logs a WARN that Swagger is on; intended for the demo |
| CloudFront to EC2 | Plain HTTP |
| Port 8080 | Accepts any CloudFront distribution (low risk; secret origin header later) |
| SES sender | A Gmail address, so mail may land in spam (no DKIM alignment) |
| Admin password output | Goes stale once the password is changed in the app |

Fresh image `cffc1c3` pushed to ECR on 17 Nov, 0 scan findings.

### Still open

W8 `deploy.yml` (GitHub OIDC, calls `campus-deploy`), a first booking on AWS, and updating the AWS deployment diagram.

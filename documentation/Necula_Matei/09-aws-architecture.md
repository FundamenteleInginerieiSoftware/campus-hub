# AWS account setup and architecture (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-10-23
**See also:** `docs/diagrams/aws-architecture.drawio`, `docs/adr/001`–`005`, `docs/decisions.md` §4

---

## Part 1: In simple terms

1. **Checked the AWS account is safe.** The main ("root") login already had a second factor (MFA) and no keys. Nothing was running that costs money.
2. **Set a spending alarm.** AWS now emails me at $5, $20 and $40 of usage per month. Our account has $100 of free credits, and the alarm counts usage *before* credits, so I notice early if something was left running.
3. **Chose Frankfurt** (`eu-central-1`) as the region for everything.
4. **Kept the free credits.** The plan wanted a newer login system (Identity Center), but turning it on would end the free plan and its $100. I wrote down why and what the risk is.
5. **Drew the cloud architecture, and kept it simple:** one server runs the whole app (website + API) in a container, a managed database stores the data, CloudFront gives us HTTPS, and a few small AWS services store photos, send emails, keep passwords and collect logs.
6. **Wrote 5 short decision records (ADRs)**, one per important choice, with the reasons and the alternatives, so anyone can answer "why did you do it this way?".

**Result:** the account is ready for the AWS work in W4+, and the team knows what the cloud version will look like. Nothing is created on AWS yet: we develop locally (ADR-001).

---

## Part 2: Technical details

### Files
| File | What |
|---|---|
| `docs/diagrams/aws-architecture.drawio` | The AWS diagram (draw.io, official AWS icons). Edit it in draw.io |
| `docs/diagrams/aws-architecture.png` | PNG export of the diagram, for the report and ADR-002. Re-export after every edit |
| `docs/adr/001-local-first.md` | Develop locally; AWS only for deploys and the demo; everything through Terraform |
| `docs/adr/002-single-ec2-behind-cloudfront.md` | One EC2 `t3.small` + Docker behind CloudFront, RDS PostgreSQL 18, no ECS/ALB/NAT |
| `docs/adr/003-ssm-parameter-store-for-secrets.md` | Secrets in SSM Parameter Store, not Secrets Manager |
| `docs/adr/004-spring-events-now-sqs-later.md` | Spring `@TransactionalEventListener` now, SQS only as a stretch goal |
| `docs/adr/005-aws-account-free-plan.md` | Free plan kept, no Organizations / Identity Center, budget alerts |
| `docs/decisions.md` | 5 new rows in §4 linking the ADRs |

### Account audit (2026-10-23, read-only CLI checks)
| Check | Command | Result |
|---|---|---|
| Identity | `aws sts get-caller-identity` | account `685527430088` (`mateinpunct`), IAM user `terraform-admin` |
| Root MFA / keys | `aws iam get-account-summary` | `AccountMFAEnabled: 1`, `AccountAccessKeysPresent: 0` |
| Plan | `aws freetier get-account-plan-state` | `FREE`, $100 credits, expires 2027-03-17 |
| Running resources | `ec2 describe-instances`, `rds describe-db-instances`, NAT, Elastic IPs, `s3 ls` (us-east-1 + eu-central-1) | none |
| Identity Center | `sso-admin list-instances` | none |

### What I changed
| Change | How |
|---|---|
| Budget `campus-hub-monthly` | `aws budgets create-budget`: $20/month, `COST`, alerts ACTUAL > 25 % / 100 % / 200 % and FORECASTED > 100 %, email to my address. `IncludeCredit: false` (default is true, which would show $0 until the credits are gone) |
| CLI region | `aws configure set region eu-central-1` (was `us-east-1`) |
| Terraform | 1.6.3 (Kali package) → **1.16.7**, official HashiCorp zip in `~/.local/bin`, GPG signature + SHA256 checked. Needed for the S3 backend's `use_lockfile` (W4) |

"AWS User Notifications" in the console is empty, and that is fine: Budgets sends its emails itself (`budgets@costalerts.amazonaws.com`).

### Architecture in one table
| Layer | Service | Key setting |
|---|---|---|
| Entry | CloudFront | `*.cloudfront.net` HTTPS; no caching on `/api/*`; `Authorization` header forwarded |
| App | EC2 `t3.small` + Elastic IP, Docker | Spring Boot jar with the React build inside; port 8080 open **only to CloudFront** (prefix list); no SSH, Session Manager instead |
| Data | RDS PostgreSQL 18 `db.t4g.micro` | private subnets, port 5432 **only from the EC2 security group**, encrypted |
| Files / email | S3 (private), SES (sandbox) | used by my `S3StorageService` and `SesNotificationService` (`@Profile("prod")`) behind Liviu's interfaces |
| Secrets | SSM Parameter Store | `/campus/*`, read at deploy into a root-only env file |
| Deploy | GitHub Actions → ECR → SSM Run Command | OIDC role, image tag = commit SHA, health check after restart |

### Why not the plan's ECS + ALB
About half the cost (~$35 vs ~$65/month when running 24/7) and far fewer pieces to configure and explain. The price is no high availability (one server, short downtime on deploy), which the report lists as a known limitation, with ECS + ALB + Multi-AZ RDS as the production answer. Details and alternatives: ADR-002.

### How it connects to teammates
| Who | What changes for them |
|---|---|
| Liviu | Nothing: his `StorageService` / `NotificationService` interfaces get my S3/SES versions in prod |
| Calin | Nothing: his local event listener also runs in prod (ADR-004) |
| Stefania | Prod serves her React build from the Spring app (🟡, she confirms). Dev stays on the Vite proxy |
| Everyone | Nobody needs AWS access; everything runs locally (ADR-001) |

# AWS pause and the RDS auto-start alert (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-17
**Branch / PR:** `infra/rds-auto-start-alert` (#309)
**See also:** `54-terraform-deploy.md`, `45-terraform-data.md`

---

## Part 1: In simple terms

1. **What happened:** on 17 Nov the first deploy (#304) worked. Nobody needs the app live for the next days, so AWS was paused to save credits: the server was stopped, then the database.
2. **The catch:** AWS does not let a database stay stopped for ever. After 7 days it starts it again by itself, and it bills again. Nobody would notice.
3. **What this PR adds:** a small alert. When AWS restarts the database on its own, it sends an e-mail. Behind it: a mailing list (an SNS topic) with one e-mail subscriber, and a rule that sends the database's "I was started again" notice to that list.
4. **One manual step:** AWS mails a confirmation link when the subscription is created. It must be clicked once, or no alert arrives.
5. **Cost of a pause:** about $0.25 a day (fixed address, disk, database storage) against about $1.2 a day while everything runs.

**Result:** the alert is written and planned (4 resources to add, 0 changed, 0 destroyed). The `terraform apply` is still to be run by Matei; the confirmation click comes after it.

| Status | |
|---|---|
| Written, `terraform plan` | done: 4 to add, 0 to change, 0 to destroy |
| `terraform apply` | **pending** (Matei runs it) |
| Click the e-mail confirmation link | **pending**, after the apply |

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `infra/alerts.tf` (new) | `aws_sns_topic.alerts` (`campus-hub-alerts`), its policy, the e-mail subscription, `aws_db_event_subscription.rds` |
| `infra/variables.tf` | New variable `alert_email` (no default, set in `terraform.tfvars`) |
| `infra/terraform.tfvars.example` | New placeholder line `alert_email` |

### Key points

| Resource | Why |
|---|---|
| `aws_sns_topic` | One topic for all alerts. Budget or CloudWatch alarms can publish to it later. Nothing runs on a laptop. |
| `aws_sns_topic_policy` | Allows our own account (`AWS:SourceOwner`) and the RDS event service `events.rds.amazonaws.com`, the latter only with `aws:SourceAccount` equal to our account, so RDS of another account cannot publish. |
| `aws_sns_topic_subscription` (`email`) | Terraform cannot confirm it: AWS mails a link, it is clicked once after the apply. |
| `aws_db_event_subscription` | `source_type = "db-instance"`, category `notification`, which carries RDS-EVENT-0154 (started again after 7 days stopped). `count = var.db_enabled ? 1 : 0`, like `rds.tf`, so it exists only with the database. |

No instance IDs, host names or addresses are in the repo; the address comes from `terraform.tfvars` (gitignored).

### Pause and resume

| Step | Command |
|---|---|
| Pause 1: stop the server | `aws ec2 stop-instances --instance-ids <id>` (the id: `terraform output app_instance_id`) |
| Pause 2: stop the database | `aws rds stop-db-instance --db-instance-identifier campus-hub` |
| Resume 1: start the database | `aws rds start-db-instance --db-instance-identifier campus-hub` |
| Resume 2: wait | `aws rds wait db-instance-available --db-instance-identifier campus-hub` |
| Resume 3: start the server | `aws ec2 start-instances --instance-ids <id>` |

The order matters: stop the app before its database; start the database before the app. The Elastic IP keeps the address and CloudFront needs no change. A stopped RDS is restarted by AWS after 7 days, so a pause longer than that must be repeated (the alert says when).

| Cost | Per day |
|---|---|
| Running (server, database, address) | about $1.2 |
| Paused (Elastic IP, EBS disk, RDS storage) | about $0.25 |

### Connects to

No application code changes. It builds on `rds.tf` (`aws_db_instance.main`, `db_enabled`) from `45-terraform-data.md` and the first deploy in `54-terraform-deploy.md`. No teammate module is touched.

### Tests

| What | Result |
|---|---|
| `terraform plan` | 4 to add, 0 to change, 0 to destroy |
| `terraform apply`, alert e-mail received | not done yet |

### Problems and fixes

| Problem | Fix |
|---|---|
| A stopped RDS silently restarts after 7 days and bills again | Event subscription on RDS-EVENT-0154 with an e-mail alert |
| Terraform cannot confirm an e-mail subscription | Documented one-time click after the first apply |

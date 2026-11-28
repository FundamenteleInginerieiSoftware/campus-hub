# CloudWatch operational alarms and graceful container stop (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-28
**Branch / PR:** `infra/alerts-and-graceful-stop` (#376)
**See also:** `54-terraform-deploy.md`, `56-rds-auto-start-alert.md`, `59-deploy-workflow.md`, `68-audit-platform-fixes.md`

---

## Part 1: In simple terms

Two operational gaps remained in our AWS setup (`infra/`):

1. **No e-mail alerts when the live app or database runs into trouble:** until now, our `campus-hub-alerts` mailing list (`#309`) only warned when AWS automatically restarted a paused database after 7 days. If the app server froze, CPU stayed stuck high, the database ran low on disk space, or the app started logging bursts of `ERROR` lines, nobody received an e-mail.
2. **Hard kill on every deploy:** our deploy script on the server (`/usr/local/bin/campus-deploy`) replaced the running app with `docker rm -f`, which cuts power to the container immediately. Any request in progress was dropped, and the 10-second e-mail drain added to `campus-ses-` in `#366` never had a chance to finish sending queued e-mails.
3. **What this PR changes:**
   - Adds **5 free-tier CloudWatch alarms** (server health check, server CPU, database free storage, database CPU, and bursts of `ERROR` lines in the app log) that all e-mail the existing `campus-hub-alerts` SNS topic.
   - Tells every alarm to ignore missing metrics while we pause EC2 and RDS between demos, so pausing AWS never spams the inbox.
   - Adds a **15-second graceful stop** (`docker stop -t 15`) before removing the old container during deploys, giving Spring Boot and the e-mail thread pool time to finish cleanly.

**Result:** Terraform formatting, validation, and rendered shell syntax checks all pass. No new AWS cost (5 of 10 free-tier alarms and 1 of 10 free-tier custom metrics). The changes take effect on the next `terraform apply`, which will also recreate the stateless EC2 app server so the updated `campus-deploy` script is installed on first boot.

---

## Part 2: Technical details

### Files changed

| File | Change |
|---|---|
| `infra/alerts.tf` | Add `CloudWatchAlarms` publish statement to `aws_sns_topic_policy.alerts`; add 5 `aws_cloudwatch_metric_alarm` resources (`ec2_status`, `ec2_cpu`, `rds_storage`, `rds_cpu`, `app_errors`) and 1 `aws_cloudwatch_log_metric_filter` (`app_errors`) |
| `infra/user-data.sh.tftpl` | Run `docker stop -t 15 campus-hub 2>/dev/null || true` before `docker rm -f campus-hub 2>/dev/null || true` inside `/usr/local/bin/campus-deploy` |

### Why `SIGKILL` broke `#366` SES drain and how `docker stop -t 15` fixes it

- `Dockerfile` uses exec-form `ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]`, so the JVM runs as PID 1 inside the `campus-hub` container and receives Unix signals directly.
- Previously, `/usr/local/bin/campus-deploy` only called `docker rm -f campus-hub`, which sends `SIGKILL` immediately. The kernel terminates the JVM without running shutdown hooks, aborting in-flight HTTP requests instead of letting Spring Boot 4's default graceful shutdown drain them, and completely bypassing `AsyncConfig.mailTaskExecutor` (`campus-ses-`, `#366`), which sets `waitForTasksToCompleteOnShutdown = true` and `awaitTerminationSeconds = 10`.
- Calling `docker stop -t 15 campus-hub 2>/dev/null || true` before `docker rm -f campus-hub 2>/dev/null || true` sends `SIGTERM` to PID 1 first and waits up to 15 seconds (leaving 5 s of headroom above the 10 s SES pool shutdown timeout) before falling back to `SIGKILL` and cleaning up the container record.

### CloudWatch operational alarms (free tier)

All 5 alarms target `aws_sns_topic.alerts.arn` (`campus-hub-alerts`), stay within AWS CloudWatch's always-free tier (5 of 10 standard alarms; 1 of 10 custom metrics), and set `treat_missing_data = "notBreaching"`.

| Terraform resource | Alarm name | Metric (Namespace) | Window & stat | Threshold | Scope / notes |
|---|---|---|---|---|---|
| `aws_cloudwatch_metric_alarm.ec2_status` | `campus-hub-ec2-status` | `StatusCheckFailed` (`AWS/EC2`) | `Maximum`, `60 s × 2` (2 min) | `>= 1` | `InstanceId = aws_instance.app.id`; catches wedged OS or AWS host hardware failure |
| `aws_cloudwatch_metric_alarm.ec2_cpu` | `campus-hub-ec2-cpu` | `CPUUtilization` (`AWS/EC2`) | `Average`, `300 s × 3` (15 min) | `> 80%` | `InstanceId = aws_instance.app.id`; warns before `t3.small` (`cpu_credits = "standard"` in `compute.tf`) drains credits |
| `aws_cloudwatch_metric_alarm.rds_storage` | `campus-hub-rds-low-storage` | `FreeStorageSpace` (`AWS/RDS`) | `Average`, `300 s × 1` (5 min) | `< 2147483648` (2 GiB) | `DBInstanceIdentifier = aws_db_instance.main[0].identifier`; `count = var.db_enabled ? 1 : 0`; 2 GiB runway on the 20 GB `gp3` volume (`rds.tf`) |
| `aws_cloudwatch_metric_alarm.rds_cpu` | `campus-hub-rds-cpu` | `CPUUtilization` (`AWS/RDS`) | `Average`, `300 s × 3` (15 min) | `> 80%` | `DBInstanceIdentifier = aws_db_instance.main[0].identifier`; `count = var.db_enabled ? 1 : 0` |
| `aws_cloudwatch_metric_alarm.app_errors` | `campus-hub-app-errors` | `ErrorCount` (`CampusHub/App`) | `Sum`, `300 s × 1` (5 min) | `>= 10` | Fed by `aws_cloudwatch_log_metric_filter.app_errors` matching `"ERROR"` in `/campus-hub/app` (`storage.tf`) |

Key configuration details:

- **SNS topic policy (`CloudWatchAlarms`):** `aws_sns_topic_policy.alerts` adds a third statement allowing `Service = "cloudwatch.amazonaws.com"` to call `sns:Publish` on `aws_sns_topic.alerts.arn`, restricted by `Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } }` (matching the existing `RdsEvents` statement so no other AWS account can publish to the topic).
- **`treat_missing_data = "notBreaching"`:** when EC2 and RDS are stopped between demos to save AWS credits (`56-rds-auto-start-alert.md`), metrics stop arriving. Treating missing data as non-breaching avoids false alerts during pauses.
- **`default_value = "0"` on `aws_cloudwatch_log_metric_filter.app_errors`:** emits a `0` datapoint whenever a 5-minute window contains log activity with no `ERROR` lines, so the alarm transitions cleanly back to `OK` instead of lingering on missing data after an error spike ends.

### Connects to

No teammate code or application files are modified:

- `infra/alerts.tf` builds on `aws_sns_topic.alerts` (`#309`), `aws_instance.app` (`infra/compute.tf`), `aws_db_instance.main` (`infra/rds.tf`, gated on `var.db_enabled`), and `aws_cloudwatch_log_group.app` (`infra/storage.tf`).
- `infra/user-data.sh.tftpl` protects in-flight requests across all modules (Liviu's catalog/audit/live streams, Calin's reservations/reassignment, Stefania's incidents/user admin) and lets `AsyncConfig.mailTaskExecutor` (`campus-ses-`, `#366`) finish draining `SesNotificationService` e-mails during deploys invoked by `.github/workflows/deploy.yml` (`#320`).

### Operational note: EC2 replacement on the next `terraform apply`

Because `infra/compute.tf` sets `user_data_replace_on_change = true` (cloud-init only runs `user-data.sh.tftpl` on first boot), the next `terraform apply` will recreate the stateless EC2 app server (`aws_instance.app`) so `/usr/local/bin/campus-deploy` gets the updated `docker stop -t 15` step. All persistent state lives outside EC2 (RDS for PostgreSQL, S3 for photos, SSM Parameter Store for config/secrets), and `aws_eip.app`, `aws_cloudfront_distribution.app` (via the Elastic IP's DNS), `aws_iam_role_policy.github_deploy` (`infra/github-deploy.tf`), and the new EC2 alarms (`ec2_status`, `ec2_cpu`) all follow `aws_instance.app.id` dynamically.

### Verification

| Command | Result |
|---|---|
| `terraform fmt -check -diff` in `infra/` | Exit 0 (clean) |
| `terraform init -backend=false && terraform validate` in `infra/` (`Terraform v1.16.7`, `hashicorp/aws v6.72.0`) | `Success! The configuration is valid.` |
| Rendered shell syntax check (`bash -n`) on `infra/user-data.sh.tftpl` | OK (exit 0) |

### Problems and fixes

| Problem | Fix |
|---|---|
| `docker rm -f` in `campus-deploy` sent `SIGKILL`, dropping in-flight HTTP requests and defeating `#366`'s 10 s `campus-ses-` shutdown drain | Added `docker stop -t 15 campus-hub` (with stderr silenced and fallback to `true`) ahead of `docker rm -f` |
| Stopping EC2/RDS between demos halts metric delivery and could fire false alarms | Set `treat_missing_data = "notBreaching"` on all 5 alarms and `default_value = "0"` on the log metric filter |
| Custom SNS topic policy would block CloudWatch from publishing alarms | Added `CloudWatchAlarms` statement for `cloudwatch.amazonaws.com` scoped with `aws:SourceAccount` |

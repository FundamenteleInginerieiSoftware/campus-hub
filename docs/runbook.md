# Operational Runbook — Campus Equipment & Incident Hub

**Owner:** Matei Necula (Member 1: cloud, security, auth) · **Verified against:** `main` (`545e334`, 28 Nov 2026)  
**See also:** [`docs/report/aws-and-cicd.md`](report/aws-and-cicd.md), [`docs/report/security.md`](report/security.md), [`docs/testing/defense-playbook.md`](testing/defense-playbook.md)

Every command below uses region **`eu-central-1`** (ADR-005) and matches the live Terraform resources in [`infra/`](../infra/). Run AWS CLI commands from the repository root with `terraform-admin` credentials configured in `~/.aws/credentials`.

---

## 1. Resource Quick Reference

| Resource | Name / Value | Source |
|---|---|---|
| AWS Region | `eu-central-1` (Frankfurt) | `infra/variables.tf` |
| EC2 Instance tag | `Name=campus-hub-app` (`t3.small`, AL2023 x86, Elastic IP) | `infra/compute.tf` |
| RDS Instance ID | `campus-hub` (PostgreSQL 18, `db.t4g.micro`, DB/user `campus`) | `infra/rds.tf` |
| ECR Repository | `campus-hub` (immutable commit-SHA tags, keep last 10) | `infra/storage.tf` |
| CloudWatch Log Group | `/campus-hub/app` (7-day retention) | `infra/storage.tf` |
| SSM Parameter Prefix | `/campus/` (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `AWS_REGION`, `PHOTO_BUCKET`, `MAIL_FROM`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`) | `infra/ssm.tf` |
| SNS Alert Topic | `campus-hub-alerts` (RDS 7-day auto-start alert `RDS-EVENT-0154`) | `infra/alerts.tf` |
| GitHub Actions Deploy | `.github/workflows/deploy.yml` (OIDC role `campus-hub-github-deploy`) | `infra/github-deploy.tf` |

Helper shell variables (run once per terminal session):

```bash
export AWS_REGION=eu-central-1
export INSTANCE_ID="$(terraform -chdir=infra output -raw app_instance_id)"
export APP_URL="$(terraform -chdir=infra output -raw app_url)"
```

---

## 2. Pause AWS (Save Credits Between Demos)

**Order matters:** stop the EC2 application server *before* stopping RDS so Spring Boot does not log connection errors while shutting down. Paused cost drops from **~$1.20/day** to **~$0.25/day** (Elastic IP + EBS + RDS storage).

```bash
# 1. Stop EC2 first
aws ec2 stop-instances --region eu-central-1 --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-stopped --region eu-central-1 --instance-ids "$INSTANCE_ID"

# 2. Stop RDS second
aws rds stop-db-instance --region eu-central-1 --db-instance-identifier campus-hub
aws rds wait db-instance-stopped --region eu-central-1 --db-instance-identifier campus-hub
```

> **7-day AWS catch (`#309`):** AWS automatically restarts a stopped RDS instance after 7 days. When that happens, `campus-hub-rds` publishes `RDS-EVENT-0154` to SNS topic `campus-hub-alerts` and sends an email to `alert_email`. If the app is still paused, run step 2 (`aws rds stop-db-instance`) again. While EC2 is stopped, merges to `main` still keep `deploy.yml` green—it prints a `::notice::` and skips cleanly.

---

## 3. Resume AWS (Before M2, Demos, or Testing)

**Order matters:** start RDS *before* EC2 so PostgreSQL is accepting SSL connections when Docker restarts `campus-hub` on boot. The Elastic IP and CloudFront origin DNS never change.

```bash
# 1. Start RDS and wait until it accepts connections (~3–5 min)
aws rds start-db-instance --region eu-central-1 --db-instance-identifier campus-hub
aws rds wait db-instance-available --region eu-central-1 --db-instance-identifier campus-hub

# 2. Start EC2 (Docker's --restart unless-stopped starts the last container automatically)
aws ec2 start-instances --region eu-central-1 --instance-ids "$INSTANCE_ID"
aws ec2 wait instance-running --region eu-central-1 --instance-ids "$INSTANCE_ID"

# 3. Verify health through CloudFront (wait ~60–120 s for Spring Boot on t3.small)
curl -fsS "$APP_URL/actuator/health" && echo
```

If new commits landed on `main` while AWS was paused, deploy the latest `main` immediately:

```bash
gh workflow run deploy
gh run watch
```

---

## 4. Trigger a Deploy or Roll Back to an Older Commit SHA

### Option A — Via GitHub Actions (`deploy.yml`, preferred)

`deploy.yml` runs automatically after every green `ci` run on `main`. You can also invoke it manually to redeploy latest `main` or roll back to **any commit on `main`**:

```bash
# Deploy newest main
gh workflow run deploy
gh run watch

# Roll back to a specific older commit SHA on main (short or full SHA accepted)
gh workflow run deploy -f sha=<older-main-commit-sha>
gh run watch
```

Because ECR tags are immutable (`infra/storage.tf`), if `<sha>` is still among the last 10 images in ECR, `deploy.yml` skips `docker build`/`docker push` and switches the container directly.

### Option B — Direct SSM Run Command (If GitHub Actions Is Unavailable)

List recent commit-SHA tags in ECR and run `/usr/local/bin/campus-deploy <full-sha>` directly on EC2:

```bash
# 1. List available image tags in ECR (newest last)
aws ecr describe-images --region eu-central-1 --repository-name campus-hub \
  --filter tagStatus=TAGGED \
  --query 'sort_by(imageDetails,&imagePushedAt)[].[imagePushedAt,imageTags[0]]' \
  --output table

# 2. Switch the container to a specific tag via SSM Run Command
SHA="<full-40-char-commit-sha>"
CMD_ID=$(aws ssm send-command --region eu-central-1 \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "manual campus-deploy $SHA" \
  --parameters "{\"commands\":[\"/usr/local/bin/campus-deploy $SHA\"]}" \
  --query Command.CommandId --output text)

# 3. Inspect command status and output
aws ssm get-command-invocation --region eu-central-1 \
  --command-id "$CMD_ID" --instance-id "$INSTANCE_ID" \
  --query '{Status:Status,Stdout:StandardOutputContent,Stderr:StandardErrorContent}'

# 4. Verify CloudFront health
curl -fsS "$APP_URL/actuator/health" && echo
```

---

## 5. RDS Daily Snapshot Restore Drill (W10)

`infra/rds.tf` keeps `backup_retention_period = 1` (one daily automated snapshot). Run this **non-destructive** drill into a temporary instance so the live `campus-hub` database is untouched:

```bash
# 1. Find the latest automated snapshot
SNAP_ID=$(aws rds describe-db-snapshots --region eu-central-1 \
  --db-instance-identifier campus-hub --snapshot-type automated \
  --query 'sort_by(DBSnapshots,&SnapshotCreateTime)[-1].DBSnapshotIdentifier' \
  --output text)
echo "Latest snapshot: $SNAP_ID"

# 2. Restore into a temporary private instance in the same subnet group & DB security group
DB_SG=$(terraform -chdir=infra output -raw db_security_group_id)
aws rds restore-db-instance-from-db-snapshot --region eu-central-1 \
  --db-instance-identifier campus-hub-restore-drill \
  --db-snapshot-identifier "$SNAP_ID" \
  --db-instance-class db.t4g.micro \
  --db-subnet-group-name campus-hub \
  --vpc-security-group-ids "$DB_SG" \
  --no-publicly-accessible

aws rds wait db-instance-available --region eu-central-1 \
  --db-instance-identifier campus-hub-restore-drill

DRILL_HOST=$(aws rds describe-db-instances --region eu-central-1 \
  --db-instance-identifier campus-hub-restore-drill \
  --query 'DBInstances[0].Endpoint.Address' --output text)
echo "Drill endpoint: $DRILL_HOST"
```

Verify data integrity from an EC2 SSM shell (`aws ssm start-session --target "$INSTANCE_ID"`):

```bash
# Inside the SSM shell on EC2 (reads DB_PASSWORD from /etc/campus.env; never echo the password)
sudo bash -c '
  source /etc/campus.env
  docker run --rm -e PGPASSWORD="$DB_PASSWORD" postgres:18 \
    psql "host='"$DRILL_HOST"' port=5432 dbname=campus user=campus sslmode=require" \
    -c "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;" \
    -c "SELECT count(*) AS users FROM users; SELECT count(*) AS assets FROM assets; SELECT count(*) AS reservations FROM reservations;"
'
```

**Always delete the temporary drill instance immediately afterward** so it stops billing:

```bash
aws rds delete-db-instance --region eu-central-1 \
  --db-instance-identifier campus-hub-restore-drill \
  --skip-final-snapshot
```

---

## 6. Rotate SSM Secrets (`DB_PASSWORD`, `JWT_SECRET`, `ADMIN_PASSWORD`)

Terraform creates `/campus/DB_PASSWORD`, `/campus/JWT_SECRET`, and `/campus/ADMIN_PASSWORD` from `random_password.*` resources (`infra/ssm.tf`). Rotating through Terraform keeps RDS, SSM, and remote state aligned.

### Standard rotation (recommended — keeps Terraform state in sync)

```bash
# Rotate JWT_SECRET (immediately invalidates all active 8 h JWTs & changes current 30 s pickup codes)
terraform -chdir=infra apply -replace=random_password.jwt

# OR rotate DB_PASSWORD (updates both RDS master password via apply_immediately=true and /campus/DB_PASSWORD)
terraform -chdir=infra apply -replace=random_password.db

# Apply the new /etc/campus.env and restart the container on EC2
gh workflow run deploy && gh run watch
```

### Emergency manual rotation (if a secret leaked and you must act in seconds)

```bash
# JWT_SECRET (must be >= 32 UTF-8 bytes; 64 hex chars = 64 bytes)
NEW_JWT="$(openssl rand -hex 32)"
aws ssm put-parameter --region eu-central-1 \
  --name /campus/JWT_SECRET --type SecureString --overwrite --value "$NEW_JWT"

# DB_PASSWORD (letters + digits only: RDS forbids /, @, ", spaces; update RDS + SSM together)
NEW_DB_PW="$(openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c 32)"
aws rds modify-db-instance --region eu-central-1 \
  --db-instance-identifier campus-hub \
  --master-user-password "$NEW_DB_PW" --apply-immediately
aws ssm put-parameter --region eu-central-1 \
  --name /campus/DB_PASSWORD --type SecureString --overwrite --value "$NEW_DB_PW"

# Restart container via SSM so campus-deploy re-reads /campus/* into /etc/campus.env
aws ssm send-command --region eu-central-1 \
  --instance-ids "$INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --parameters '{"commands":["/usr/local/bin/campus-deploy"]}'
```

> **Note on `ADMIN_PASSWORD`:** `AdminBootstrap` only creates the first admin when `admin_email` does not yet exist (`#88`); it never overwrites an existing user's password hash. Rotating `/campus/ADMIN_PASSWORD` in SSM updates the bootstrap parameter for fresh databases only. Until a self-service change-password endpoint lands on `main`, changing an already-created admin's password requires updating its `{bcrypt}` hash in `users` directly (or creating a second admin via `/admin/users` and disabling the old account).

---

## 7. Verify SES Sandbox Email Addresses

The AWS account operates in the **SES sandbox**: both `MAIL_FROM` (created by `infra/ses.tf`) and every demo recipient address must click their one-time AWS verification email before SES delivers mail to them.

```bash
# Send verification email to a teammate or demo inbox
aws sesv2 create-email-identity --region eu-central-1 \
  --email-identity teammate@example.com

# Check verification status of one address
aws sesv2 get-email-identity --region eu-central-1 \
  --email-identity teammate@example.com \
  --query '{Verified:VerifiedForSendingStatus,Status:VerificationStatus}'

# List all SES identities in eu-central-1
aws sesv2 list-email-identities --region eu-central-1 --output table
```

---

## 8. Inspect CloudWatch Logs & Server Diagnostics (No SSH)

### CloudWatch Logs (`/campus-hub/app`, 7-day retention)

```bash
# Live tail of Spring Boot container logs (last 30 minutes + follow)
aws logs tail /campus-hub/app --region eu-central-1 --since 30m --follow

# Filter for errors or Flyway/startup issues
aws logs filter-log-events --region eu-central-1 \
  --log-group-name /campus-hub/app \
  --filter-pattern "ERROR" \
  --query 'events[].message' --output text
```

### Interactive Shell via SSM Session Manager

```bash
aws ssm start-session --region eu-central-1 --target "$INSTANCE_ID"
```

Useful checks inside the Session Manager shell:

```bash
sudo docker ps -a
sudo docker logs --tail 100 campus-hub
sudo stat -c '%a %U:%G %n' /etc/campus.env          # expect: 600 root:root /etc/campus.env
sudo cat /var/log/cloud-init-output.log | tail -n 50
sudo dnf upgrade --releasever=latest -y             # AL2023 OS security patching before a demo
```

---

## 9. Local PostgreSQL Fallback (Offline Demo & Scratch PG)

Per ADR-001, the full application and defense demo can run locally with zero AWS dependency. There is no separate `start.sh` wrapper on `main`; use the commands below directly.

### Standard local fallback (system PostgreSQL 18 on `:5432`)

```bash
# 1. Start PostgreSQL 18 and confirm it answers
sudo service postgresql start                       # Linux/WSL
pg_isready -h localhost

# (Optional) Reset local dev database to a clean seed state
sudo -u postgres psql -c "DROP DATABASE IF EXISTS campus WITH (FORCE); CREATE DATABASE campus OWNER campus;"

# 2. Terminal 1 — Backend (http://localhost:8080, seeds 3 dev accounts + catalog + sample booking)
cd backend && ./gradlew bootRun

# 3. Terminal 2 — Frontend (http://localhost:5173, proxies /api to :8080)
cd frontend && npm ci && npm run dev
```

Dev accounts (password `password` for all): `student1@campus.test`, `student2@campus.test`, `admin@campus.test`.

### Isolated scratch PostgreSQL 18 cluster (when system PG is down or busy)

Useful for running backend tests or a clean rehearsal on a separate port (e.g. `:5433`) without touching the system service:

```bash
# 1. Initialize & start a temporary PostgreSQL 18 cluster (adjust /usr/lib/postgresql/18/bin if needed)
PG_BIN="/usr/lib/postgresql/18/bin"
SCRATCH_PG="$(mktemp -d /tmp/campus-pg-XXXXXX)"
"$PG_BIN/initdb" -D "$SCRATCH_PG" -U postgres --auth=trust >/dev/null
"$PG_BIN/pg_ctl" -D "$SCRATCH_PG" -o "-p 5433 -k $SCRATCH_PG" -l "$SCRATCH_PG/pg.log" start

# 2. Create the campus user and databases
psql -h localhost -p 5433 -U postgres -c "CREATE ROLE campus LOGIN PASSWORD 'campus';"
psql -h localhost -p 5433 -U postgres -c "CREATE DATABASE campus OWNER campus;"
psql -h localhost -p 5433 -U postgres -c "CREATE DATABASE campus_test OWNER campus;"

# 3. Run the backend OR test suite against port 5433
DB_URL="jdbc:postgresql://localhost:5433/campus" backend/gradlew -p backend bootRun
# OR run full build/tests:
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/campus_test" \
  backend/gradlew -p backend --no-daemon build

# 4. Stop and remove the scratch cluster when finished
"$PG_BIN/pg_ctl" -D "$SCRATCH_PG" stop
rm -rf "$SCRATCH_PG"
```

---

## 10. Post-Grading Teardown (`terraform destroy`)

After the final `v1.0.0` defense and grade confirmation, remove all billable AWS infrastructure in one command:

```bash
# 1. Confirm no leftover restore-drill RDS instances or manual snapshots exist
aws rds describe-db-instances --region eu-central-1 --query 'DBInstances[].DBInstanceIdentifier'
aws rds describe-db-snapshots --region eu-central-1 --snapshot-type manual --query 'DBSnapshots[].DBSnapshotIdentifier'

# 2. Destroy the main Terraform stack (CloudFront, EC2, EIP, RDS, S3 photos, ECR, SSM, SNS, OIDC, VPC)
cd infra
terraform plan -destroy
terraform destroy
```

**Notes on teardown:**
- `rds.tf` sets `deletion_protection = false` and `skip_final_snapshot = true`, `storage.tf` sets `force_destroy = true` on `campus-hub-photos-*` and `force_delete = true` on ECR `campus-hub`, so `terraform destroy` completes cleanly without manual console clicks.
- The state bucket `campus-hub-tfstate-099cd54b` (`infra/bootstrap/main.tf`) has `lifecycle { prevent_destroy = true }` and stores only a few kilobytes (effectively $0/month). Leave it intact until grading is completely final; if closing the AWS account entirely, remove `prevent_destroy`, empty all object versions in S3, and destroy `infra/bootstrap/`.
- Check **AWS Billing → Cost Explorer** 48 hours after `terraform destroy` to confirm $0.00/day ongoing spend.

# Terraform part 1: state bucket, network and firewall (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth (owner of `infra/`)
**Date:** 2026-11-09
**Branch / PR:** `infra/terraform-network`
**See also:** `09-aws-architecture.md`, ADR-001 (local first), ADR-002 (one EC2 behind CloudFront), ADR-003 (secrets in SSM), `45-terraform-data.md` (part 2)

---

## Part 1: In simple terms

1. **Everything on AWS is written down as code (Terraform).** Instead of clicking in the AWS console, the `infra/` folder describes what should exist. One command creates it, one command deletes it. So we can delete everything between work phases to save credits, and recreate it in minutes (ADR-001).
2. **Terraform keeps a "list of what I created"** (the state). That list will contain passwords (part 2), so it must never go into GitHub. It lives in a private, encrypted S3 bucket instead.
3. **That bucket is created once, by a tiny separate setup** (`infra/bootstrap/`). It can't be created by the main setup, because the main setup needs the bucket to exist before it starts. Terraform refuses to delete it by accident.
4. **A private network for the app (VPC).** It has three small parts:
   - one **public** part for the app server (comes in W7), which can reach the internet;
   - two **private** parts for the database, with no way in or out of the internet at all. AWS asks for two, in two different buildings (availability zones), even for one database.
5. **A firewall chain** (security groups):
   - only **CloudFront** (the HTTPS front door) may talk to the app server, on port 8080. Calling the server's address directly doesn't work;
   - only the **app server** may talk to the database, on port 5432;
   - there is **no SSH port**. When we need a shell on the server, we use AWS Session Manager, which needs no open port.
6. **No paid network parts.** No NAT gateway (~$35/month): the server sits in the public part and talks to AWS directly.

**Result:** the network and firewall for ADR-002 exist as code, checked by `terraform validate`. Nothing here costs money except a few cents for the state bucket. Part 2 (`45-terraform-data.md`) adds the database, the photo bucket, the image registry, the server's permissions and the secrets.

---

## Part 2: Technical details

### Files

| File | What it holds |
|---|---|
| `infra/bootstrap/main.tf` | The state bucket `campus-hub-tfstate-<suffix>`: versioning, AES256 encryption, public access blocked, `prevent_destroy`. Its own state is local |
| `infra/bootstrap/.terraform.lock.hcl` | Exact provider versions + hashes (aws 6.72.0, random 3.9.1) |
| `infra/versions.tf` | Terraform `>= 1.10`, providers `aws ~> 6.72`, `random ~> 3.9`; `default_tags` `Project = campus-hub`, `ManagedBy = terraform` |
| `infra/backend.tf` | S3 backend: bucket from bootstrap, key `campus-hub/terraform.tfstate`, `encrypt = true`, `use_lockfile = true` |
| `infra/variables.tf` | `aws_region` (default `eu-central-1`, ADR-005), `vpc_cidr` (default `10.0.0.0/16`) |
| `infra/network.tf` | VPC, internet gateway, 1 public subnet + route table, 2 DB subnets + a route table with no internet route |
| `infra/security_groups.tf` | `campus-hub-app` and `campus-hub-db` groups and their 4 rules |
| `infra/outputs.tf` | `vpc_id`, `public_subnet_id`, `db_subnet_ids`, `app_security_group_id`, `db_security_group_id` (for W7) |
| `infra/.terraform.lock.hcl` | Same provider pins as bootstrap |
| `.gitignore` | `infra/.terraform/` → `infra/**/.terraform/` (also covers `bootstrap/`), new `*.tfplan`. `*.tfstate*` and `*.tfvars` were already there |

### Key choices

| Choice | Why |
|---|---|
| State in S3, never in git | It will hold the generated DB password and JWT secret (part 2, ADR-003). The bucket is encrypted, versioned (a broken apply can be rolled back by hand) and never public |
| Separate `bootstrap/` with a local state | Chicken-and-egg: the backend bucket must exist before `terraform init` of `infra/`. The bootstrap state only knows one bucket, no secrets; losing it is harmless (the bucket keeps working, it's just not managed anymore) |
| `prevent_destroy` on the state bucket | Deleting it would orphan every resource Terraform made. Also: `terraform destroy` in `infra/` never touches it, because it lives in another config |
| Random suffix in bucket names | Bucket names are global across all AWS accounts. A suffix avoids clashes without putting our account ID in a public repo |
| `use_lockfile = true` | S3 locks the state while someone runs plan/apply (Terraform 1.10+), so no DynamoDB lock table is needed. Why `>= 1.10` |
| `default_tags` | Every resource gets `Project = campus-hub`, so Cost Explorer can filter our bill and the console shows what Terraform owns |
| Subnets: `cidrsubnet(var.vpc_cidr, 8, …)` | Public `10.0.1.0/24` (AZ a); DB `10.0.11.0/24` (AZ a), `10.0.12.0/24` (AZ b) |
| Two DB subnets in two AZs | RDS refuses a subnet group with one AZ, even for single-AZ |
| DB route table without routes | Only the built-in `local` route: the database can reach the VPC and nothing else |
| `map_public_ip_on_launch` on the public subnet | Without a NAT gateway, the server needs a public IP to pull from ECR and talk to SSM. The security group still lets only CloudFront in |
| `enable_dns_hostnames` | RDS gives a host name, not an IP; the app resolves it through the VPC's DNS |
| App ingress = CloudFront prefix list `com.amazonaws.global.cloudfront.origin-facing` | AWS keeps the list current. Nobody can skip HTTPS by calling the server IP. Note: this one rule counts as 55 of the 60 inbound rules per group |
| No port 22 | Shell through SSM Session Manager (role in part 2) |
| App egress: 443 anywhere + 5432 to the DB group | ECR, SSM, S3, SES, CloudWatch Logs and OS updates all use HTTPS. DNS and time on AWS link-local addresses need no rule |
| DB group: no egress rules | Terraform removes AWS's default "allow all out"; the database never starts a connection. Replies are allowed anyway (security groups are stateful) |
| Rules as separate `aws_vpc_security_group_*_rule` resources | Changing one rule doesn't replace the whole group |
| "From the app group", not from an IP | Whichever server carries the app group may connect, even after it is replaced and gets a new IP |

### How it connects

| Who / what | Uses |
|---|---|
| Part 2 (`45-terraform-data.md`) | `aws_subnet.db[*]` for the RDS subnet group, `aws_security_group.db` for RDS |
| W7 deploy (EC2 + CloudFront) | `public_subnet_id`, `app_security_group_id` from `terraform output` |
| Teammates | Nothing changes. Everyone develops on a laptop (ADR-001); nobody needs AWS access |

### How to run

Only Matei runs this, with the `terraform-admin` CLI profile (`09-aws-architecture.md`).

1. **Once, ever:** create the state bucket.
   ```bash
   cd infra/bootstrap && terraform init && terraform apply
   ```
   It prints `state_bucket = "campus-hub-tfstate-<suffix>"`.
2. **Once:** paste that name into `infra/backend.tf` (done: `campus-hub-tfstate-099cd54b`) and commit it. The name is not a secret.
3. **Every time:**
   ```bash
   cd infra && terraform init && terraform plan && terraform apply
   ```
   `plan` shows what would change; `apply` asks for `yes` first.

| File | Where it lives |
|---|---|
| State of `infra/` | S3: `campus-hub-tfstate-<suffix>/campus-hub/terraform.tfstate`. Never in git |
| State of `bootstrap/` | `infra/bootstrap/terraform.tfstate` on my laptop, ignored by git (`*.tfstate`). Harmless to lose |
| Provider downloads | `infra/**/.terraform/`, ignored, recreated by `terraform init` |
| Lock files | `.terraform.lock.hcl` committed, so every run uses the same provider builds |

### Cost

| What | Cost |
|---|---|
| VPC, subnets, route tables, internet gateway, security groups | Free |
| State bucket (a few KB, versioned) | A few cents per month |
| Public IPv4 | Nothing yet: billed only once a server gets one (W7) |

### Testing

| Check | Result |
|---|---|
| `terraform fmt -check -recursive` (in `infra/`) | No output, exit 0 |
| `terraform validate` in `infra/` and in `infra/bootstrap/` | `Success! The configuration is valid.` (Terraform 1.16.7) |
| `git check-ignore -v infra/.terraform infra/bootstrap/bootstrap.tfplan` | Both ignored (`infra/**/.terraform/`, `*.tfplan`) |

<!-- TODO Claude: plan/apply results -->

### Problems and open points

| Point | Notes |
|---|---|
| Backend values can't use variables | Terraform reads `backend.tf` before variables exist, so the bucket name and region are written out. If `aws_region` ever changes, `backend.tf` and `bootstrap/main.tf` must change by hand too |
| CloudFront prefix list weight | 55 of 60 inbound rules used in the app group. A future rule should go in a second group |
| CloudFront → EC2 is plain HTTP | Accepted in ADR-002 (only CloudFront can reach 8080). Production would add a certificate on the origin |

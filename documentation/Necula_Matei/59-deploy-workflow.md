# Automatic deploy to AWS with GitHub OIDC (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-20
**Branch / PR:** `infra/deploy-workflow` (#320)
**See also:** `54-terraform-deploy.md` (server, `campus-deploy`), `56-rds-auto-start-alert.md` (pause/resume), `50-docs-check.md`, ADR-002

---

## Part 1: In simple terms

1. **Before:** to put a new version on AWS, I built the Docker image on my laptop, pushed it and logged into the server by hand.
2. **Now:** every time a pull request is merged and the `ci` robot is green on `main`, a second robot (`deploy`) does it alone: it builds the image, uploads it, tells the server to switch to it, and waits until the app answers "UP".
3. **No password in GitHub:** GitHub never holds an AWS key. For every run GitHub signs a short note: "this is the campus-hub repository, branch main". AWS checks the signature and lends a key that expires after an hour. A copy of the repository, a pull request branch or anyone else gets nothing.
4. **The key opens very little:** upload images to our one image registry, run our deploy command on our one server, and read the result. It can't read our secrets, delete anything or stop the server.
5. **When AWS is paused** (to save credits), the robot says so and skips; it doesn't turn red. After a resume, one command deploys the newest `main`.
6. **Going back:** the same robot, started by hand with an older commit of `main`, puts that version back.

| Status | |
|---|---|
| Written, `terraform plan` | done: 3 to add, 0 to change, 0 to destroy |
| `terraform apply` | **pending** (Matei) |
| Repository variables `AWS_DEPLOY_ROLE_ARN`, `APP_URL` | **pending** (Matei) |
| First live run | **pending**, after the AWS resume |

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `infra/github-deploy.tf` (new) | `aws_iam_openid_connect_provider.github`, role `campus-hub-github-deploy` (trust: our repo on `main` only), inline least-privilege policy |
| `infra/outputs.tf` | New output `github_deploy_role_arn` |
| `.github/workflows/deploy.yml` (new) | After a green `ci` on `main` (or by hand): build, push to ECR, SSM `campus-deploy <sha>`, health check through CloudFront |
| `docs/diagrams/deployment-aws.puml` + `.png`, `deployment-local.puml` + `.png` | Deploy path no longer "planned"; `ci` note says `deploy.yml` deploys after it |

### Why OIDC, and why scoped to the branch

| Choice | Why |
|---|---|
| OIDC, not an IAM user with keys | No long-lived secret in GitHub to leak or rotate; credentials last 1 hour (ADR-002, ADR-005) |
| Trust `sub` = `repo:FundamenteleInginerieiSoftware@338709262/campus-hub@1407699804:ref:refs/heads/main` (StringEquals, plus `aud` = `sts.amazonaws.com`) | Only runs on `main`, which already needs a PR, a review and a green `ci`. Our repo uses GitHub's **immutable subject** format (`name@id`; checked with `gh api repos/FundamenteleInginerieiSoftware/campus-hub/actions/oidc/customization/sub`), so a repo recreated under the same name by someone else doesn't match |
| Branch, not a GitHub environment | An environment (`...:environment:production`) needs extra repo settings (branch rules on the environment) or any branch could use it. The branch scope works with the settings we already have |

`workflow_run` jobs run in the context of the default branch, so their token says `ref:refs/heads/main` too.

### Permissions of the deploy role

| Sid | Actions | Resource | Why |
|---|---|---|---|
| `EcrLogin` | `ecr:GetAuthorizationToken` | `*` (AWS allows nothing else) | `docker login` to ECR |
| `EcrPush` | `BatchCheckLayerAvailability`, `InitiateLayerUpload`, `UploadLayerPart`, `CompleteLayerUpload`, `PutImage`, `DescribeImages` | repo `campus-hub` only | Push; `DescribeImages` skips the build when the commit's image already exists (tags are immutable) |
| `FindServer` | `ec2:DescribeInstances` | `*` (no resource-level support) | Skip when the server is stopped |
| `RunDeployScript` | `ssm:SendCommand` | document `AWS-RunShellScript` + our instance ARN | Run `campus-deploy <sha>` on our server only |
| `ReadDeployResult` | `ssm:GetCommandInvocation` | `*` (no resource-level support) | Wait for the script and print its output |

Checked with the IAM policy simulator: allowed for our server, the shell document and our repo; denied for another instance, another document, another repo, `ecr:BatchDeleteImage`, `ssm:GetParameter` on `/campus/*`, `ec2:StopInstances`.
Accepted risk: `SendCommand` runs as root on the server, so a run on `main` could read the server's secrets. That is why the trust is limited to `main` (reviewed code only).

### The workflow, step by step

| Step | What | Notes |
|---|---|---|
| Trigger | `workflow_run` of `ci` (completed, branch `main`) + `workflow_dispatch` (optional `sha`) | Job runs only if `ci` succeeded on a **push** in this repo (a fork PR can also have a branch called `main`) |
| Pick the commit | `head_sha` of the `ci` run, or the input (short SHA ok), default newest `main` | Must be on `main` (`git merge-base --is-ancestor`), so an unmerged commit can't be deployed by hand. An automatic run for a commit that is no longer the newest skips (two quick merges: the ci runs can finish in any order) |
| Not set up yet | `vars.AWS_DEPLOY_ROLE_ARN` or `vars.APP_URL` empty → `::notice::`, skip | Merges stay green until the variables exist |
| AWS login | `aws-actions/configure-aws-credentials@v6` with the OIDC role, `eu-central-1` | `permissions: id-token: write, contents: read` |
| Server running? | `describe-instances` by tag `Name=campus-hub-app`, state `running` | None → `::notice::` and skip (AWS paused) |
| Image | `amazon-ecr-login@v2`, `docker build` + `docker push` `campus-hub:<full sha>` | Skipped if that tag is already in ECR |
| Deploy | `ssm send-command AWS-RunShellScript "/usr/local/bin/campus-deploy <sha>"`, poll up to 10 min, print stdout/stderr | Fails the job unless `Success` |
| Health | `curl $APP_URL/actuator/health` until `"status":"UP"`, 3 min | Through CloudFront, like a user |

`concurrency: deploy`, `cancel-in-progress: false`: one deploy at a time, never stopped halfway.

### What Matei must do once

| # | Command (from `~/campus/infra` after the merge, or from the branch's `infra/`) |
|---|---|
| 1 | `terraform apply` (expect 3 to add, 0 to change, 0 to destroy) |
| 2 | `gh variable set AWS_DEPLOY_ROLE_ARN --body "$(terraform output -raw github_deploy_role_arn)"` |
| 3 | `gh variable set APP_URL --body "$(terraform output -raw app_url)"` |
| 4 | After the AWS resume (doc 56): `gh workflow run deploy`, then `gh run watch` |

Variables, not secrets: the role ARN and the CloudFront address are not secret (the trust policy, not the ARN, protects the role).

Rollback: `gh workflow run deploy -f sha=<older main commit>`.

### Connects to

No application code changes. Reuses `campus-deploy` from `user-data.sh.tftpl` (`54-terraform-deploy.md`), the ECR repo and its immutable SHA tags (`storage.tf`), the server's Name tag (`compute.tf`) and CloudFront (`cdn.tf`). It starts after Stefania's `ci.yml` but doesn't change it. `/actuator/health` is public in `SecurityConfig`.

### Tests

| What | Result |
|---|---|
| `terraform fmt -check`, `terraform validate` | OK |
| `terraform plan` (real state, `-lock=false`) | 3 to add, 0 to change, 0 to destroy |
| IAM policy simulator | allowed/denied as in the permissions table |
| `actionlint` 1.7.12 | no findings (without shellcheck) |
| `bash -n` on every `run:` block | OK |
| "Pick the commit" step run locally in a clone | newest main, old short SHA, non-main commit (error), garbage input (error), stale automatic run (skip), variables unset (skip): all as expected |
| Live run | pending (needs apply + variables + AWS resumed) |

### Problems and fixes

| Problem | Fix |
|---|---|
| The repo's OIDC `sub` is in the new immutable format (`owner@id/repo@id`), not `repo:owner/repo:...` | Found with `gh api .../actions/oidc/customization/sub`; the trust policy uses that exact value. With the old format, AWS would refuse every run |
| `ci` also runs for pull requests, and a fork can name its branch `main` | Job condition requires `workflow_run.event == 'push'` and the same repository |
| `workflow_dispatch` with a `sha` could deploy an unreviewed PR commit | The commit must be an ancestor of `origin/main` |
| ECR tags are immutable: a re-run or rollback would fail on `docker push` | Check `describe-images` first, reuse the image |
| `aws ssm wait command-executed` gives up after 100 s | Own polling loop, 10 min |
| Before the variables exist, every merge would show a red deploy | Skip with a notice while `AWS_DEPLOY_ROLE_ARN` / `APP_URL` are empty |

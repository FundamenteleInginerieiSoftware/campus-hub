# ADR-005: AWS account stays on the Free plan, no AWS Organizations

**Status:** Accepted · **Date:** 2026-10-23 · **Owner:** Matei

## Context
The account (`mateinpunct`, created 17 Sep 2026) is on the credit-based **Free plan**: $100 of credits, valid until 17 Mar 2027 (after the defense). The plan suggested signing in through **IAM Identity Center**. Giving Identity Center users access to the account requires **AWS Organizations**, and enrolling a Free-plan account in Organizations moves it to the Paid plan and stops the free credits.

## Decision
| Item | State |
|---|---|
| Root user | MFA on, no access keys, not used for daily work |
| Daily work | IAM user `terraform-admin` (administrator) with an access key, on Matei's laptop only (`~/.aws/credentials`) |
| Organizations / Identity Center | **not used** (to keep the credits) |
| Region | `eu-central-1` (Frankfurt), the CLI default |
| Budget | `campus-hub-monthly`: $20/month, email alerts at $5 actual, $20 actual, $20 forecast, $40 actual. **Credits are not subtracted**, so the alerts show real usage while credits pay for it |
| GitHub Actions | OIDC role, **no AWS keys in GitHub** (ADR-002) |

## Why
The $100 credits pay for the whole deployed phase (ADR-002 estimates ~$35/month at most, less when stopped). Identity Center would be the cleaner login, but losing the credits costs real money for a course project.

## Consequences
- **Known risk:** the `terraform-admin` key does not expire and has no MFA. If it leaks, someone gets full access. Mitigations: it exists only on Matei's laptop, never in the repo, prompts or CI; budget alerts catch unexpected usage; it can be deactivated in seconds in IAM.
- Possible later improvement without Organizations: MFA on `terraform-admin` + `aws login` (temporary credentials from a console sign-in) and delete the access key.

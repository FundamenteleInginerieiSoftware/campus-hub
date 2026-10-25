# ADR-003: Secrets in SSM Parameter Store, not Secrets Manager

**Status:** Accepted · **Date:** 2026-10-23 · **Owner:** Matei

## Context
Prod needs a few secrets: the DB password, the JWT signing secret (≥ 32 random bytes) and the first admin's email + password (admin bootstrap). They must never be in Git, in the Docker image or in GitHub secrets. `application-prod.yml` already reads everything from environment variables.

## Decision
- Store them as **SSM Parameter Store `SecureString`** parameters under `/campus/` (for example `/campus/DB_PASSWORD`, `/campus/JWT_SECRET`), encrypted with the AWS-managed KMS key.
- Terraform generates the DB password and the JWT secret (`random_password`) and writes them to SSM. The Terraform state therefore contains them, which is one more reason it lives only in the encrypted S3 backend (never committed).
- The deploy script on EC2 reads `/campus/*` with the instance role, writes them to an env file only root can read (`chmod 600`) and starts the container with `--env-file`. Spring sees normal environment variables, so **no extra library** (no Spring Cloud AWS) is needed.

## Why
| | Parameter Store (standard) | Secrets Manager |
|---|---|---|
| Price | **free** | $0.40 per secret per month + API calls |
| Encryption | KMS | KMS |
| Automatic rotation | no | yes (for RDS passwords) |
| Fits our ~4 secrets | yes | yes, but rotation is a feature we wouldn't use |

## Consequences
- No automatic password rotation. For the course we rotate by hand if a secret leaks (change it in SSM, redeploy). In the report: "production would use Secrets Manager rotation for the DB password".
- The secrets exist in a file on the server while it runs. Only root can read it, and there is no SSH access (Session Manager only).

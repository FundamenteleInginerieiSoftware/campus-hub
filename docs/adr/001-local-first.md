# ADR-001: Local-first development, AWS only for deploys and the demo

**Status:** Accepted · **Date:** 2026-10-23 · **Owner:** Matei

## Context
Four students, a 12-week course, and an AWS account on the Free plan with $100 of credits (until 17 Mar 2027). Only Matei works on AWS. Most AWS resources (EC2, RDS) cost money every hour they exist.

## Decision
- Everyone develops and tests **on their own laptop**: Spring Boot + PostgreSQL 18 (local install), React with the Vite dev server. CI uses a `postgres:18` service container. Nobody needs AWS access to do their work.
- AWS is used only for the **deployed version**: first deploy in W7-W8, the M2 check in W9, and the demo.
- All AWS resources are created with **Terraform** (`infra/`), so they can be destroyed between work phases and recreated in minutes.
- The demo has a **local fallback** on the same laptop (plus a recorded video).

## Why
| Reason | Detail |
|---|---|
| Cost | Nothing runs on AWS while we write code. The credits last for the whole course |
| Speed | Restarting the app locally takes seconds. Deploying to AWS takes minutes |
| Same database | PostgreSQL 18 locally, in CI and on RDS, so "works locally, breaks on AWS" is rare |
| Bus factor | The app does not depend on Matei's AWS account to be developed or graded |

## Consequences
- Code must not depend on AWS in dev: the AWS adapters (`S3StorageService`, `SesNotificationService`) are `@Profile("prod")`, the local ones (`LocalStorageService`, `LoggingNotificationService`, Liviu) are `@Profile("!prod")`.
- Bugs that only show up on AWS (CloudFront headers, IAM permissions, memory) are found late. That is why the first deploy is planned by W8 at the latest.

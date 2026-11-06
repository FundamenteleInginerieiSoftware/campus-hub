# Docs: all security rules in the API contract, SSM arrow fixed (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-06
**Branch / PR:** `docs/contract-security-rules` (issue #190)
**See also:** `19-dockerfile.md` (rules 6–8 came with it), `20-auth-ignores-old-token.md`, `21-firewall-error-json.md`, `22-token-account-check.md`, `27-jwt-bad-subject-401.md`, `09-aws-architecture.md` (the diagram), `docs/adr/003-ssm-parameter-store-for-secrets.md`

---

## Part 1: In simple terms

1. **The rule list in the API contract was out of date.** It showed 5 "who may open what" rules; the code has had 8 since the Docker image PR. Teammates and the frontend read the contract, not the Java file, so they saw an old picture.
2. **Now the contract lists all 8 rules, in the same order as the code,** and for each one says what a caller gets when the answer is "no" (401 = log in first, 403 = you are logged in but not allowed).
3. **It also lists the checks that happen before the rules:** strange addresses are blocked, broken or expired tokens are refused, and a blocked or deleted account loses access at once.
4. **The AWS drawing had one arrow the wrong way round.** It showed the secrets store sending secrets to the server. In our plan (ADR-003) the server fetches them itself, with its own AWS role. The arrow now points from the server to the secrets store.

**Result:** the contract matches `SecurityConfig` rule for rule, and the AWS diagram matches ADR-003 and the UML deployment diagram.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `docs/api-contract.md` §2 | Rules table: 5 → 8 rows (`/error`, `denyAll` for `/api/**` + `/actuator/**`, public `GET /**` for the SPA, `denyAll` fallback); new "Refused" column (status + `code`); new table of the checks that run before the rules |
| `docs/diagrams/aws-architecture.drawio` | Edge `e-ssm-ec2` (SSM → EC2) replaced by `e-ec2-ssm` (EC2 → SSM), label "reads secrets /campus/* + deploy command" |
| `docs/diagrams/aws-architecture.png` | **Not changed: must be re-exported by hand** (see below) |

### The 8 rules (first match wins)

| # | Path | Access | Refused |
|---|---|---|---|
| 1 | `/api/v1/auth/**` | public, token header not read (#116) | – |
| 2 | health, `/v3/api-docs/**`, Swagger UI | public | – |
| 3 | `/error` | public (else a 404 would show as 401) | – |
| 4 | `/api/v1/admin/**` | `ADMIN` | 401 / 403 `FORBIDDEN` |
| 5 | `/api/v1/**` | any active user | 401 `UNAUTHORIZED` |
| 6 | `/api/**`, `/actuator/**` | `denyAll` | 401 / 403 |
| 7 | `GET /**` | public (React app, `SpaConfig`) | – |
| 8 | anything else | `denyAll` | 401 / 403 |

`denyAll` answers **401** to a caller without a token and **403** to a caller with one: Spring sends an anonymous caller to the entry point ("log in first") before it says "forbidden". The tests show it: `SecurityConfigTest.otherApiPathsAreDeniedEvenWithAToken` and `writesOutsideTheApiAreDeniedEvenWithAToken` expect 403 with an admin token.

### Checks before the rules

| Check | Result | Where |
|---|---|---|
| Firewall (`;`, `..`, encoded `/`) | 400 `REQUEST_REJECTED` | `RequestRejectedHandler` bean (#118) |
| Bad signature, expired, not a JWT | 401 `UNAUTHORIZED` | resource server + `SecurityErrorHandler` |
| `sub` missing or not a number | 401 `UNAUTHORIZED` | `SecurityConfig.userId` (#158) |
| Account disabled or deleted | 401 `UNAUTHORIZED` | `activeUserFromToken` → `UserApi.findActiveRole` (#117) |
| Role | from the database, not the `roles` claim | same (#117) |

The rows match the RBAC table of the security chapter (`docs/report/security.md` §4, PR #185).

### The SSM arrow

| Before | After | Why |
|---|---|---|
| `source="ssm" target="ec2"`, "secrets + deploy command" | `source="ec2" target="ssm"`, "reads secrets /campus/* + deploy command" | ADR-003: the deploy script on EC2 reads `/campus/*` with the instance role (`ssm:GetParameters`). The SSM Agent on EC2 also opens the connection to pick up the Run Command, so EC2 starts both calls and needs no inbound port. Same direction as `agent → envfile (GetParameters)` in `deployment-aws.puml` (#186) |

Only `source`/`target`, the exit/entry points (swapped) and the label position (`x="0.3"` → `x="-0.3"`, so the label stays where it was) changed. The XML was checked with a parser.

**PNG:** draw.io is not installed here and has no headless export, so `aws-architecture.png` still shows the old arrow. **To do by hand:** open the `.drawio` in draw.io (desktop or app.diagrams.net) → File → Export as → PNG (same settings as before), overwrite `docs/diagrams/aws-architecture.png`, commit it to this branch.

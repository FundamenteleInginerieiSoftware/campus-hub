# 55. Swagger server URL and HEAD on public pages (#308)

## Part 1: In simple terms

**What was wrong.** After the first deploy to AWS (#304) we tried the live site and found two small problems.

1. On the Swagger page, the "Try it out" button did not work. Swagger asked the wrong address to send the request to: the internal address of the server instead of the public address of the site.
2. Asking the home page "are you there?" (a `HEAD` request, the kind uptime checkers and CDNs send) got "not allowed", while opening the page in a browser worked.

**What we did.**

1. Told Swagger to use a relative address ("the same place this page came from"). Now it works on AWS and on a laptop without any setting.
2. Allowed the "are you there?" request on the public pages, right next to the rule that already allowed opening them. The API stays closed: the rules for it come earlier in the list and still win.
3. Added one test for each fix.

**Result.** "Try it out" works on AWS. The home page answers an uptime check. Asking the API the same question without logging in is still refused.

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `backend/.../config/OpenApiConfig.java` | `servers = @Server(url = "/")` in `@OpenAPIDefinition`, with a bug-fix comment |
| `backend/.../config/SecurityConfig.java` | `requestMatchers(HttpMethod.HEAD, "/**").permitAll()` right after the `GET /**` rule, with a bug-fix comment |
| `backend/src/test/.../config/OpenApiDocsIT.java` | New test `swaggerCallsTheSameOriginAsThePage` (file already existed) |
| `backend/src/test/.../config/SpaFrontendTest.java` | New test `headOnStartPageIsPublicButHeadOnApiIsNot` (file already existed) |
| `docs/api-contract.md` | Security rule 8 now says `GET` and `HEAD` |

### Why

| Problem | Cause | Fix |
|---|---|---|
| Swagger "Try it out" fails on AWS | springdoc builds `servers` from the incoming request. CloudFront does not forward the `Host` header, so the request reaches EC2 as plain http on the EC2 host name. The HTTPS page then called an http host the browser blocks | A relative `"/"`: the browser calls the origin the page was loaded from (CloudFront on AWS, `localhost:8080` locally) |
| `HEAD /` is 401 | The static-pages rule listed only `GET`; anything unlisted falls to "denied" | A `HEAD /**` rule after the `GET /**` one. HEAD returns no body, so it opens nothing GET does not. `/api/**` and `/actuator/**` rules are earlier in the chain and still win, so `HEAD /api/...` without a token stays 401 |

### Connections

| Module | How |
|---|---|
| Stefania's React app, served by `SpaConfig` | Same pages as before; only the HEAD method is newly allowed on them |
| All teammates' controllers | Swagger lists them with the relative server, no change in their code |
| Doc 54 (Terraform part 3) | Closes its two "Known follow-ups" rows |

### Tests

| Test | What it checks |
|---|---|
| `OpenApiDocsIT.swaggerCallsTheSameOriginAsThePage` | `/v3/api-docs` has `servers[0].url` equal to `/` |
| `SpaFrontendTest.headOnStartPageIsPublicButHeadOnApiIsNot` | `HEAD /` is 200, `HEAD /api/v1/reservations/me` without a token is 401 |

The build was run locally on a scratch PostgreSQL and finished green.

### Problems and fixes

Both are the table "Why" above; each fix has a comment at the changed line.

### Still open

Nothing from this PR. Remaining deploy work: W8 `deploy.yml`, a first booking on AWS, and updating the AWS deployment diagram (doc 54).

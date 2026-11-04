# Blocked addresses get our error format (Necula Matei)

**Role:** Member 1, Cloud, Security & Auth
**Date:** 2026-11-04
**Branch / PR:** `fix/security-firewall-json` (issue #118)
**See also:** `05-security-error-json.md` (401/403 as JSON), `12-error-codes-fix.md`

---

## Part 1: In simple terms

1. **Spring Security has a "firewall"** that blocks web addresses with tricks in them, like `..;` or an encoded `/`. Attackers use them to sneak past access rules.
2. **Blocking was fine, but the answer looked different** from all our other errors: it had no short `code`, so the website couldn't tell what happened.
3. **Now a blocked address gets the same error shape as everything else,** with the code `REQUEST_REJECTED`.

**Result:** every error the API sends, from any layer, has the same fields and a `code`.

---

## Part 2: Technical details

### Files

| File | Change |
|---|---|
| `config/SecurityErrorHandler.java` | Also implements `RequestRejectedHandler`: 400 `REQUEST_REJECTED` |
| `config/SecurityConfig.java` | New `@Bean RequestRejectedHandler requestRejectedHandler(JsonMapper)` |
| `test/.../config/SecurityConfigTest.java` | `firewallRejectionIsProblemJson`: `GET /api/v1/auth/ping;x=1` → 400 problem JSON, `code = REQUEST_REJECTED` |
| `docs/api-contract.md` §5 | `REQUEST_REJECTED` row |

### How it works

| Piece | What it does |
|---|---|
| `StrictHttpFirewall` | Runs first inside `FilterChainProxy`, before any filter, and throws `RequestRejectedException` for `;`, `..`, `%2F`, etc. |
| Default handler | Called `sendError(400)`, so Boot's `/error` page wrote `{timestamp,status,error,path}` |
| `RequestRejectedHandler` bean | Spring Security picks up a bean of this type and uses it instead of the default; ours writes the same body as 401/403 (`type`, `title`, `status`, `detail`, `instance`, `code`) |

It is a `@Bean` (not `new` inside the filter chain like the 401/403 handler) because Spring looks it up in the context by type.

### Tests

| Check | Result |
|---|---|
| New test **without** the bean | failed (Boot's default JSON, no `code`) |
| With the fix: `./gradlew test --tests com.campus.config.SecurityConfigTest` | green |

<!-- PR title = squash commit message, so use Conventional Commits: feat(catalog): add asset search -->

## What / why

<!-- What does this PR change, and why? 2-5 lines are enough. -->

Closes #

## How to test

<!-- Steps a reviewer can follow, e.g.:
1. `cd backend && ./mvnw spring-boot:run`
2. Open http://localhost:8080/swagger-ui.html and log in as student1@campus.test
3. POST /api/v1/... with ... -> expect 201
-->

## Checklist

- [ ] Linked issue (`Closes #12`)
- [ ] `./mvnw verify` passes locally (tests + spotless check)
- [ ] New logic has tests (happy path + at least 1 failure case)
- [ ] Swagger annotations / example on new endpoints
- [ ] New migration file? Version number checked against `main` (handbook §8.2)
- [ ] No entity returned from a controller (DTOs only)
- [ ] `docs/api-contract.md` updated if the API changed
- [ ] Screenshots (frontend PRs)
- [ ] I can explain every line of this PR (AI-assisted code included)

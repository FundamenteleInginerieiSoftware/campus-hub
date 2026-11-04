# Campus Equipment & Incident Hub — Team Test Plan

**Document:** Software Quality Assurance & Verification Plan  
**Author / Test Lead:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Target:** FIS Course 2026 — 12-week Modular Monolith  
**Tech Stack:** Spring Boot 4.1.1, Java 25, PostgreSQL 18, Gradle, ArchUnit, JaCoCo, React + Vite + TypeScript  

---

## 1. Objectives & Scope

The purpose of this Test Plan is to define the testing strategy, quality gates, test hierarchy, and execution guidelines for the **Campus Equipment & Incident Hub** platform.

As a multi-tenant university lab management system where equipment is reserved and automatically reassigned upon equipment failure, software reliability and concurrency guarantees are critical. The testing suite must ensure that:
1. **Overlap Prevention:** No two students can reserve the same equipment item during overlapping time windows under any circumstance, including simultaneous concurrent requests.
2. **Transaction Isolation & Fault Tolerance:** Automatic reassignment runs in an isolated transaction (`REQUIRES_NEW`) after the incident report is successfully committed, preventing rollback cascades.
3. **Module Encapsulation:** Modules (`catalog`, `reservation`, `incident`, `reassignment`, `user`) communicate strictly via public `api` packages and Spring Application Events, enforced via ArchUnit.
4. **Deterministic Time Handling:** All business logic depending on time operates via a shared `Clock` bean, guaranteeing reproducible test execution independent of execution time and timezone discrepancies.
5. **Quality Gates:** Every pull request must pass spotless formatting, ArchUnit architecture rules, complete test execution (unit + integration), and meet the ≥ 70% JaCoCo line coverage threshold.

---

## 2. Test Hierarchy (Testing Pyramid)

We employ a 4-tier testing hierarchy that balances execution speed, isolation, and end-to-end correctness.

```
       / \
      / E2E\        Tier 4: Manual E2E Smoke & Workflow Checklist
     /------\
    /  Arch  \      Tier 3: ArchUnit Monolith Architecture Rules
   /----------\
  / Integration\    Tier 2: @SpringBootTest & @DataJpaTest (PostgreSQL 18)
 /--------------\
/   Unit Tests   \  Tier 1: Mockito Service Tests & Domain Unit Tests (No Spring Context)
------------------
```

### Tier 1: Unit Tests (Fast & Isolated)
- **Frameworks:** JUnit Jupiter 5, AssertJ, Mockito (`@ExtendWith(MockitoExtension.class)`).
- **Scope:** Pure business logic inside `@Service` classes, strategy implementations (`SameModelStrategy`, `SameCategoryStrategy`), entity state machines (`Reservation`, `IncidentReport`), and mapping records.
- **Rule:** **No Spring ApplicationContext is booted.** Collaborator dependencies (repositories, external module API facades) are mocked with `@Mock`. This keeps individual test runs under 100 milliseconds.
- **Clock:** Fixed clock injected via `Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneId.of("Europe/Bucharest"))`.

### Tier 2: Slice & Integration Tests (PostgreSQL 18)
- **Frameworks:** Spring Boot Test, `@DataJpaTest`, `@WebMvcTest`, `@SpringBootTest`.
- **Database:** Local and CI PostgreSQL 18 instances (`campus_test`). No H2 or in-memory DB is used to guarantee identical dialect, row locking semantics, and Flyway migration behavior.
- **Slices:**
  1. **`@WebMvcTest`**: Validates HTTP status codes (200, 201, 204, 400, 401, 403, 404, 409), validation annotations (`@NotNull`, `@Size`), and JSON RFC 9457 `ProblemDetail` structures. Security is verified using `@WithMockUser(roles = "ADMIN")`.
  2. **`@DataJpaTest`**: Validates SQL queries, Spring Data Specifications, and custom overlap JPQL queries (`r.startTime < :end AND r.endTime > :start`).
  3. **`@SpringBootTest` Integration Tests (`*IT.java`)**:
     - `ReassignmentRollbackIT`: Verifies that failures during notification or reassignment do not roll back the original incident report, and vice-versa.
     - `ReservationConcurrencyIT`: Uses multithreaded workers (`ExecutorService` + `CountDownLatch`) to fire simultaneous reservation requests for the same slot, proving pessimistic locking (`PESSIMISTIC_WRITE`) permits exactly 1 booking and rejects the other with 409 `RESERVATION_CONFLICT`.

### Tier 3: Architectural Tests (ArchUnit)
- **Framework:** ArchUnit 1.5.1 (`com.campus.architecture.ModuleBoundaryArchitectureTest` and `CodingConventionsArchitectureTest`).
- **Enforced Constraints:**
  - **Modular Monolith Encapsulation:** Non-`api` packages (`domain`, `repository`, `service`, `web`) cannot be accessed from outside their owning module.
  - **Dependency Hierarchy:** `catalog` and `reservation` must never depend on `incident` or `reassignment`.
  - **Layering:** Controllers may only depend on service/api interfaces; domain entities may not depend on web controllers.
  - **Coding Conventions:** Entities must not use Lombok `@Data`; controllers must not expose `@Entity` objects.

### Tier 4: Manual E2E Verification & UI Tests
- **Frontend Verification:** `npm run lint` and `npm run build` executed in CI to verify TypeScript types, React component signatures, and CSS styling.
- **Manual Verification Script:** 10-step end-to-end user journey across student booking, incident reporting with photo upload, automated substitute reassignment, and admin desk equipment checkout.

---

## 3. Concurrency & Transactional Verification

Special emphasis is placed on concurrency and distributed failure modes:

| Test Class | Objective | Technique | Expected Outcome |
|---|---|---|---|
| `ReservationConcurrencyIT` | Verify pessimistic row locking on equipment during booking | 2 concurrent threads synchronized with `CountDownLatch` executing `bookReservation()` | Exactly 1 transaction commits (HTTP 201); 1 transaction fails with 409 `RESERVATION_CONFLICT` |
| `ReassignmentRollbackIT` | Verify transaction autonomy of the auto-reassignment engine | Throw simulated exception during reassignment processing | Incident report remains committed in `incident_reports`; asset stays `UNDER_MAINTENANCE` |
| `AssetOptimisticLockIT` *(planned, Liviu W7–W8)* | Prevent stale asset edits | Concurrent updates on the same `Asset` entity with `@Version` | Spring Data JPA throws `OptimisticLockingFailureException` (HTTP 409 `STALE_VERSION`) |

---

## 4. Deterministic Time Strategy

Uncontrolled system time (`LocalDateTime.now()` / `Instant.now()`) causes non-deterministic test failures (e.g., tests failing when run after 20:00 closing hours, or tests failing after midnight).

### Mandate
- Direct calls to `LocalDateTime.now()` or `Instant.now()` are **forbidden** across all backend modules.
- All date/time operations must use `LocalDateTime.now(clock)` or `Instant.now(clock)`.
- The production bean `ClockConfig` supplies `Clock.system(ZoneId.of("Europe/Bucharest"))`.

### Test Configuration Pattern
In test classes requiring time manipulation, a fixed clock is provided:
```java
@TestConfiguration
static class TestClockConfig {
    @Bean
    @Primary
    public Clock testClock() {
        return Clock.fixed(
            Instant.parse("2026-11-02T10:00:00Z"),
            ZoneId.of("Europe/Bucharest")
        );
    }
}
```

---

## 5. Quality Gates & Coverage Thresholds

Every Pull Request must satisfy the following automated gates before merge:

1. **Static Analysis & Formatting:**
   ```bash
   ./gradlew spotlessCheck
   ```
   Must pass with zero formatting discrepancies.

2. **Automated Test Suite:**
   ```bash
   ./gradlew test
   ```
   All unit, slice, architectural, and integration tests must pass (currently 288+ tests).

3. **Code Coverage (JaCoCo):**
   ```bash
   ./gradlew jacocoTestCoverageVerification
   ```
   - **Line Coverage Target:** ≥ 70% on all `*.service.*` packages.
   - Verified via JaCoCo reports uploaded in CI artifacts and generated in `build/reports/jacoco/test/html/index.html`.

4. **Frontend Quality Check:**
   ```bash
   npm run lint && npm run build
   ```
   Zero TypeScript errors, zero `oxlint` warnings, Vite build completes successfully.

---

## 6. Test Environment & Database Setup

To maintain parity between local developer laptops and GitHub Actions CI:
- **Engine:** PostgreSQL 18.
- **Port:** 5432 (local default).
- **Database Names:**
  - `campus`: Local application runtime.
  - `campus_test`: Dedicated integration test execution database.
- **Credentials:** Default test environment uses `DB_USERNAME=campus`, `DB_PASSWORD=campus`.
- **Flyway Migrations:** Applied automatically on test context startup via `ddl-auto=validate`.

---

## 7. Test Naming & Coding Conventions

- **Class Names:**
  - Unit tests: `<ClassName>Test.java` (e.g., `ReservationServiceTest`, `SameModelStrategyTest`)
  - Integration tests: `<ClassName>IT.java` (e.g., `ReservationConcurrencyIT`, `ReassignmentRollbackIT`)
  - Architecture tests: `*ArchitectureTest.java` or `ArchitectureRulesTest.java`
- **Method Names:** Follow the Given-When-Then or descriptive action format:
  - `rejectsOverlappingReservation()`
  - `autoCancelsWhenNoSubstituteAvailable()`
  - `allowsCheckoutWithinGracePeriod()`
  - `throwsNotFoundWhenAssetDoesNotExist()`
- **Assertions:** Use AssertJ fluent assertions (`assertThat(result)...`) for readable, expressive failure messages.

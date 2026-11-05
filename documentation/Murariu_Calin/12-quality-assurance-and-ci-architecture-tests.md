# Quality Assurance & CI Architecture Rules (Murariu Călin)

**Member 3:** Reservations, Reassignment Engine & Test Lead ([@Swiorx](https://github.com/Swiorx))  
**Module:** `com.campus.reservation`, `com.campus.reassignment`, `com.campus.architecture`  
**Deliverable:** Unit Testing Expansion & Automated CI Architecture Enforcement  

---

## Part 1: In simple terms

### Why Architecture Tests in CI?
When 4 developers work simultaneously on a fast-paced university project, it is easy for small mistakes to slip through code reviews:
- A teammate might accidentally write `LocalDateTime.now()` instead of injecting the shared `Clock`. When the project is deployed to AWS (where servers run in UTC) or tested after 20:00 (when labs are closed), tests randomly fail.
- Someone might put `@Transactional` directly onto a `@RestController`. This keeps database connections open during HTTP serialization, slowing down the server and causing subtle transaction isolation bugs.
- Someone might map a new database enum without `@Enumerated(EnumType.STRING)`. JPA defaults to `EnumType.ORDINAL`, storing integers (`0, 1, 2`) in PostgreSQL. If someone later inserts an enum constant in the middle, all past database records silently point to the wrong status!

Instead of hoping humans remember every rule in the team handbook, we codified these requirements into **automated architecture tests (ArchUnit)** that run on every build and CI pull request. If anyone violates a project rule, the build fails immediately with a descriptive error message.

Along with architecture tests, we expanded unit and slice test suites across seeders, event listeners, domain state guards, and error responses, guaranteeing high reliability across all reservation and reassignment code.

---

## Part 2: Technical details

### 1. New Automated CI Architecture Rules (`CodingConventionsArchitectureTest`)

**File:** `backend/src/test/java/com/campus/architecture/CodingConventionsArchitectureTest.java`

#### Rule 4: Shared Clock Bean Enforcement (`AGENTS.md` §3)
```java
@ArchTest
static final ArchRule no_unparameterized_datetime_now = noClasses()
        .should()
        .callMethod(LocalDateTime.class, "now")
        .orShould()
        .callMethod(Instant.class, "now")
        .because("All date and time calculations must use the shared Clock bean (e.g. LocalDateTime.now(clock)) to ensure deterministic tests and proper time zone handling");
```
- **Why it matters:** Enforces deterministic time. Prevents hard-to-reproduce bugs between local machines (`Europe/Bucharest`) and CI/AWS runners (`UTC`). Extended in `#156` to also forbid zero-argument `LocalDate.now()`, `LocalTime.now()`, `ZonedDateTime.now()`, and `OffsetDateTime.now()`.

#### Rule 5: `@Transactional` Placement Boundary (`AGENTS.md` §5)
```java
@ArchTest
static final ArchRule controllers_must_not_be_transactional = noClasses()
        .that()
        .areAnnotatedWith(RestController.class)
        .should()
        .beAnnotatedWith(Transactional.class)
        .orShould(new ArchCondition<JavaClass>("have @Transactional methods") { ... })
        .because("@Transactional belongs on service methods only, never on controllers (AGENTS.md §5)");

@ArchTest
static final ArchRule repositories_must_not_be_transactional = noClasses()
        .that()
        .areAssignableTo(Repository.class)
        .should()
        .beAnnotatedWith(Transactional.class)
        .orShould(new ArchCondition<JavaClass>("have @Transactional methods") { ... })
        .because("@Transactional belongs on service methods only, never on repositories (AGENTS.md §5)");
```
- **Why it matters:** Guarantees that transaction lifecycles begin and end inside `@Service` business methods (also inspects individual repository methods and controller methods, avoiding `noClasses()` condition inversion). Controllers simply orchestrate DTO mapping, and repositories rely on standard Spring Data transaction boundaries.

#### Rule 6: Enum Mapping Safety (`AGENTS.md` §5)
```java
@ArchTest
static final ArchRule entities_must_not_use_ordinal_enum_mapping = fields()
        .that()
        .areDeclaredInClassesThat()
        .areAnnotatedWith(Entity.class)
        .and()
        .areAnnotatedWith(Enumerated.class)
        .should(new ArchCondition<JavaField>("use EnumType.STRING instead of ORDINAL") { ... })
        .because("Database enums must be VARCHAR(32) with EnumType.STRING to prevent silent data corruption if enum constants are reordered (AGENTS.md §5)");
```
- **Why it matters:** Protects PostgreSQL databases against silent integer-order corruption when enum values change or expand.

#### Rules 7–14: Spring Boot 4 Idioms, One-Origin CORS, `PageResponse<T>`, Lazy JPA Associations, `/api/v1/admin` Prefix, `@Entity`-Free `api` Facades, No Standard Streams & Plain-ID Cross-Module Entity References
- **`no_legacy_spring_boot_2_or_3_imports`**: Forbids `javax.persistence..`, `javax.validation..`, `com.fasterxml.jackson.databind..` (Jackson 2), and `io.jsonwebtoken..` (`jjwt`).
- **`controllers_must_not_use_cross_origin`**: Forbids `@CrossOrigin` on controller classes and individual methods (Vite dev proxy and CloudFront prod serve from a single origin).
- **`controllers_must_not_return_spring_page`**: Ensures paginated endpoints return `PageResponse<T>`, never Spring Data's `Page<T>`.
- **`entities_must_not_use_one_to_many` & `entities_many_to_one_must_be_lazy`**: Enforces `@ManyToOne(fetch = FetchType.LAZY)` and forbids `@OneToMany` on JPA entities.
- **`admin_controllers_must_map_under_admin_prefix` & `admin_endpoints_must_map_under_admin_prefix`**: Guarantees every `Admin*Controller` and any admin-mapped method (including in catalog controllers) maps under `/api/v1/admin/**` so `SecurityConfig` enforces `ROLE_ADMIN`.
- **`api_packages_must_not_expose_entities`**: Ensures no class or interface in `..api..` exposes `@Entity` types in method return types, parameter types, or record/field types, keeping cross-module contracts purely DTO/record-based.
- **`no_standard_streams`**: Forbids `System.out` and `System.err` in production code, requiring structured SLF4J logging (`@Slf4j`).
- **`entities_must_not_reference_entities_from_other_modules`**: Enforces that JPA `@Entity` fields never reference `@Entity` types from another module (`AGENTS.md` §5: cross-module references must be plain IDs such as `Long assetId`).

---

### 2. Unit & Slice Test Suite Additions

#### 1. `LocalAssetDamagedListenerTest.java`
- Tests `LocalAssetDamagedListener` in `com.campus.reassignment.service`.
- Verifies `on(AssetDamagedEvent)` delegates directly to `reassignmentService.handle(event)`.
- Validates via reflection that the method has `@Async` and `@TransactionalEventListener(phase = AFTER_COMMIT)`.

#### 2. `ReservationSeederTest.java`
- Tests `ReservationSeeder` in `com.campus.reservation.service`.
- Covers 4 scenarios:
  1. Skips when reservations table already has data (`count() > 0`).
  2. Skips when required test users (IDs 1 and 2) are not found.
  3. Skips when catalog assets have not been seeded yet.
  4. Correctly schedules 2 sample reservations on the next weekday, skipping Saturday and Sunday when run on Friday.

#### 3. `ReservationTest.java` Domain Transitions
- Added `markOverdueValidFromCheckedOut`: verifies transition from `CHECKED_OUT` to `OVERDUE`.
- Added `markOverdueInvalidFromOtherStatuses`: parameterized test over 6 non-valid statuses (`CONFIRMED`, `RETURNED`, `CANCELLED`, `FORCE_CANCELLED`, `OVERDUE`, `NO_SHOW`), verifying `InvalidStateTransitionException` (`INVALID_RESERVATION_STATUS_TRANSITION`).
- Added `markNoShowInvalidFromOtherStatuses`: parameterized test over 6 non-`CONFIRMED` statuses, verifying `InvalidStateTransitionException` (`INVALID_RESERVATION_STATUS_TRANSITION`).

#### 4. `ReservationControllerTest.java` Web Slices
- Added `checkAvailabilityCategoryReturns200`: verifies bulk category equipment availability queries return 200 OK with available asset IDs.
- Added `createWithInvalidBodyReturns400`: missing payload fields trigger 400 with `VALIDATION_FAILED` (RFC 9457).
- Added `createOutsideOpeningHoursReturns409`: `OUTSIDE_OPENING_HOURS` business rule conflict yields 409 (`BusinessRuleException`).
- Added `createWithConflictReturns409`: `RESERVATION_CONFLICT` slot collision yields 409 with problem detail code.
- Added `checkAvailabilityMissingBothIdsReturns409`: querying availability without IDs yields 409 `MISSING_PARAMETER`.

#### 5. `AdminReservationControllerTest.java` Web Slices
- Added `returnItemReturns404WhenNotFound`: missing reservation returns 404 `RESERVATION_NOT_FOUND`.
- Added `returnItemReturns409WhenInvalidTransition`: returning already cancelled reservation returns 409 `INVALID_RESERVATION_STATUS_TRANSITION`.

#### 6. `SubstituteNoLongerFreeExceptionTest.java`
- Verifies constructor message and cause propagation for reassignment concurrency exceptions.

---

### 3. Verification & CI Status
- Backend test suite: `./gradlew spotlessApply build` executes 308 tests with 100% pass rate.
- JaCoCo test report: Module service coverage exceeds the 70% threshold.
- Frontend: `npm run lint` and `npm run build` pass with zero errors.

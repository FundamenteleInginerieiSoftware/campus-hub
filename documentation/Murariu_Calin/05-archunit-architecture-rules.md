# Architecture Rules: ArchUnit Module Boundaries (Murariu Călin)

**Role:** Member 3, Reservations, Auto-Reassignment & Architecture  
**Date:** 2026-10-31  
**Package:** `com.campus.architecture`  
**Branch / PR:** `test/architecture-module-rules`  
**See also:** `AGENTS.md` §1 (Calin owns ArchUnit rules), §5 (Architecture essentials)

---

## Part 1: In simple terms

When four developers build a modular monolith simultaneously, it is easy for boundaries to erode: someone needs a piece of data and directly imports another module's internal database repository or service instead of asking for a clean public API facade. Over time, this turns a clean modular architecture into an unmaintainable "spaghetti" system with circular dependencies where changing one table breaks unrelated modules.

### The Problem
- **Hidden coupling:** A module can accidentally depend on implementation details of another module (e.g. `catalog` directly querying `reservationRepository`).
- **Circular dependencies:** Module A calls Module B, which calls Module C, which calls Module A. This makes independent testing, refactoring, and eventual extraction into microservices impossible.
- **Architectural drift:** Code reviews can miss a single unwanted import statement.

### The Solution: Automated Architecture Testing with ArchUnit
We introduced **ArchUnit** tests running as standard JUnit 5 test cases during `./gradlew test` and in the GitHub Actions CI pipeline:
1. **Public API Enclosure:** Each feature and shared service module (`catalog`, `incident`, `notification`, `reassignment`, `reservation`, `storage`, `user`) exposes *only* its `api` package. Internal packages (`domain`, `service`, `repository`, `web`) are strictly forbidden from being imported by other modules.
2. **Forbidden Cross-Module Dependencies:** 
   - `catalog` is the core equipment inventory and must never depend on reservations, incidents, or reassignment.
   - `reservation` handles booking lifecycles and must not depend on incidents or reassignment.
   - `incident` publishes damage events and must not depend on reassignment.
3. **No Circular Dependencies:** A clean directed acyclic graph (DAG) is enforced between all top-level modules.
4. **Coding Conventions:**
   - Constructor injection only (field injection via `@Autowired` is banned).
   - Controllers in `web` must only return and accept request/response records (DTOs), never JPA `@Entity` domain objects (including generic wrappers like `ResponseEntity<Asset>` or `List<Asset>`).
   - Entities must not define `equals`, `hashCode`, or `toString` (forbidden Lombok `@Data`, `@EqualsAndHashCode`, `@ToString` or hand-written).

If any teammate accidentally violates these rules, the build breaks immediately locally and in CI with an explicit, educational failure message.

---

## Part 2: Technical details

### Files Created

| File | Type | Description |
|---|---|---|
| `backend/src/test/java/com/campus/architecture/ModuleBoundaryArchitectureTest.java` | ArchUnit JUnit 5 Test | Enforces module API isolation, forbidden dependencies, and acyclic slicing |
| `backend/src/test/java/com/campus/architecture/CodingConventionsArchitectureTest.java` | ArchUnit JUnit 5 Test | Enforces constructor injection, entity encapsulation in controllers, and Lombok restrictions |

---

### Enforced Rules Matrix

| Rule | ArchUnit Definition | Rationale & AGENTS.md Reference |
|---|---|---|
| **Public API Only** | `noClasses().that().resideInAPackage("..source..").should().dependOnClassesThat().resideInAnyPackage("..target.domain..", "..target.service..", "..target.repository..", "..target.web..")` | Encapsulates module internals; cross-module contracts are public interfaces/records in `api/` (AGENTS.md §5). |
| **Catalog Independence** | `noClasses().that().resideInAPackage("com.campus.catalog..").should().dependOnClassesThat().resideInAnyPackage("..reservation..", "..incident..", "..reassignment..")` | Catalog is foundational; availability is computed by `reservation`, not `catalog` (AGENTS.md §5). |
| **Reservation Independence** | `noClasses().that().resideInAPackage("com.campus.reservation..").should().dependOnClassesThat().resideInAnyPackage("..incident..", "..reassignment..")` | Reservations must not couple to incident reports or the reassignment engine. |
| **Incident Independence** | `noClasses().that().resideInAPackage("com.campus.incident..").should().dependOnClassesThat().resideInAPackage("..reassignment..")` | Incident reporting publishes `AssetDamagedEvent`; reassignment is decoupled as a listener. |
| **No Cycles** | `slices().matching("com.campus.(*)..").should().beFreeOfCycles()` | Prevents circular dependencies across all top-level modules. |
| **No Field Injection** | `GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION` | Enforces constructor injection with `@RequiredArgsConstructor` (AGENTS.md §5). |
| **DTOs in Controllers (Returns & Params)** | `methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class).and().arePublic().should(notExposeEntities)` | Controllers must return and accept response/request records, preventing JPA domain entity leakage even through generic wrappers like `ResponseEntity<Asset>` or `List<Asset>` (AGENTS.md §3). |
| **Forbidden Equals/HashCode on Entities** | `noMethods().that().areDeclaredInClassesThat().areAnnotatedWith(Entity.class).should().haveName("equals").orShould().haveName("hashCode").orShould().haveName("toString")` | `@Data`, `@EqualsAndHashCode`, and `@ToString` break JPA proxy identity and dirty checking; because Lombok has `SOURCE` retention, ArchUnit checks that these generated or hand-written methods do not exist (AGENTS.md §5). |

---

### How it Connects to Teammates' Work

- **Matei (Cloud & Security):** Added `archunit-junit5:1.5.1` to `build.gradle`. These architecture tests run in GitHub Actions (`.github/workflows/ci.yml`) on every pull request.
- **Liviu (Catalog):** Validates that new catalog endpoints and services remain completely decoupled from booking or incident logic.
- **Stefania (Incidents & CI):** Ensures incident reporting interacts with assets purely via `CatalogApi` and notifies reassignment via `AssetDamagedEvent` without direct class imports.
- **Calin (Reservations & Reassignment):** Maintains clear separation between the booking engine (`reservation`) and the strategy-based reassignment engine (`reassignment`).

---

### Verification & Test Output

```bash
cd backend
./gradlew test --tests "*ArchitectureTest"
```

Output:
```
> Task :test
BUILD SUCCESSFUL in 11s
6 actionable tasks: 3 executed, 3 up-to-date
```
All architecture rules pass against the entire `com.campus` codebase with 0 violations.

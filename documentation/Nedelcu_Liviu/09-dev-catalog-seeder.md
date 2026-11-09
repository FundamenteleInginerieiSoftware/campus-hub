# 09. Development Catalog Seeder

## Part 1: In Simple Terms

When developing and demonstrating the Campus Hub application, starting with an empty database requires manually creating categories, laboratory rooms, and equipment one by one. If the database is reset or deployed in a local development environment, all other features (such as student bookings and automated incident reassignment) cannot function because there are no physical assets to book or reassign.

The `CatalogSeeder` acts as an automated "stocking crew":
- Whenever the application boots up in development mode (`dev` profile), it checks if the catalog already contains data.
- If the catalog is empty, it automatically provisions:
  - **4 Categories:** Oscilloscopes, Multimeters, Microcontrollers, and 3D Printers.
  - **3 Lab Rooms:** A101 (Electronics Lab), B204 (Embedded Systems Lab), and C305 (Prototyping Lab).
  - **18 Assets:** Carefully organized across labs and categories.
- **Why multiple units of the same model?** Crucially, it creates groups of identical equipment (e.g. 3 units of the `DS1054Z` digital oscilloscope). When an incident is reported on unit #1, the disaster-recovery reassignment engine can automatically find an available duplicate unit (unit #2) in the same laboratory and seamlessly transfer upcoming student reservations without human intervention.
- It also seeds a sample equipment with retired status (`RETIRED`) so developers and testers can immediately test catalog filtering, badge rendering, and status transition constraints (note: assets are not seeded `UNDER_MAINTENANCE` without an active incident report; see doc 15 / #230).

---

## Part 2: Technical Details

### Architectural Position & Ordering

Spring Boot executes `CommandLineRunner` beans ordered via `@Order(n)`:

| Component | Owner | Order | Profile | Role |
|---|---|---|---|---|
| `UserSeeder` | Matei Necula | `@Order(1)` | `dev` | Seeds dev accounts (`student1`, `student2`, `admin`) with id 1, 2, 3 |
| **`CatalogSeeder`** | **Liviu Nedelcu** | **`@Order(2)`** | **`dev`** | **Seeds 4 categories, 3 lab rooms, 18 assets** |
| `ReservationSeeder` | Călin Murariu | `@Order(3)` | `dev` | Queries lowest AVAILABLE asset (id 1) and creates sample bookings |

### Provisioned Catalog Data

| Category | Lab Room | Assets Count | Models & Serial Patterns | Statuses |
|---|---|---|---|---|
| Oscilloscopes | A101, B204 | 5 | `DS1054Z` (x3: `RIGOL-DS1054Z-001..003`), `DSOX1204G` (x2: `KEYSIGHT-1204G-001..002`) | All `AVAILABLE` |
| Multimeters | A101, B204 | 4 | `Fluke 115` (x3: `FLUKE-115-001..003`), `DM3058E` (x1: `RIGOL-DM3058E-001`) | All `AVAILABLE` |
| Microcontrollers | B204 | 5 | `STM32F407G-DISC1` (x3: `STM32-DISC-001..003`), `ESP32-S3-DevKitC-1` (x2) | All `AVAILABLE` (#230) |
| 3D Printers | C305 | 4 | `Prusa i3 MK3S+` (x2), `X1-Carbon` (x1), `Photon Mono` (x1) | 3 `AVAILABLE`, 1 `RETIRED` |

### Key Design Principles

1. **Idempotency Guard:** `if (categoryRepository.count() > 0 || labRoomRepository.count() > 0 || assetRepository.count() > 0) return;` prevents duplicate key violations on application restarts without wiping the database.
2. **Deterministic Second Timestamps:** Timestamps use `LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS)` to adhere to the ISO-8601 second precision contract.
3. **Reassignment Preparedness:** Asset 1 (`RIGOL-DS1054Z-001`, room A101) is guaranteed to be `AVAILABLE`, allowing `ReservationSeeder` to attach sample bookings. Asset 2 (`RIGOL-DS1054Z-002`, room A101) serves as an immediate same-model substitute for the incident demonstration.

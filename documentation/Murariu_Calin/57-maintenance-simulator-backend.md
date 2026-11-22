# 57 — Maintenance Impact Simulator Backend (#58, PR 1)

## Part 1: In simple terms

Administrators can now run a read-only dry-run simulation (`POST /api/v1/admin/reassignments/simulate`) before taking an equipment unit offline for maintenance. It previews which upcoming bookings would be moved to a substitute vs. force-cancelled, using the exact same `SameModelStrategy` and `SameCategoryStrategy` rules as the live reassignment engine, without changing any rows in the database.

---

## Part 2: Technical details

| Artifact | Purpose |
|---|---|
| `MaintenanceImpactSimulator.java` | `@Transactional(readOnly = true)` service reusing `List<SubstituteStrategy>` with an in-memory `virtualAllocations` map so overlapping simulated bookings never double-book a substitute. |
| `AdminReassignmentController.java` | Added `POST /api/v1/admin/reassignments/simulate` (protected under `/api/v1/admin/**`). |
| `SimulateMaintenanceRequest.java`, `MaintenanceImpactResponse.java`, `SimulatedBookingImpact.java` | Request/response DTO records including `disruptionScore = 1 * reassigned + 3 * forceCancelled`. |
| `MaintenanceImpactSimulatorTest.java`, `AdminReassignmentControllerTest.java` | Unit and WebMvc security tests (403 for students, 200 for admins, virtual double-allocation prevention, `INVALID_TIME_RANGE`). |

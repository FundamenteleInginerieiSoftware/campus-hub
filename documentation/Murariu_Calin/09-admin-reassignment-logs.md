# Document 09: Admin Reassignment Logs Endpoint

**Author:** Călin Murariu ([@Swiorx](https://github.com/Swiorx))  
**Module:** `com.campus.reassignment`  
**Endpoint:** `GET /api/v1/admin/reassignments`  

---

## Part 1: In Simple Terms

When lab equipment breaks and a student reports an incident, our automatic disaster-recovery engine steps in: it finds substitute equipment of the same model (or category) and transfers upcoming student bookings so lab sessions aren't disrupted. If no substitute is available, it force-cancels the booking so the student receives an immediate notice instead of arriving at the lab to find broken equipment.

Every action taken by this engine is recorded in an audit table called `reassignment_log`.

This document describes the administrative endpoint `GET /api/v1/admin/reassignments`. It provides lab administrators and staff with a clear, chronological history (newest first) of all machine-driven decisions:
- Which incident caused the reassignments?
- Which student reservation was affected?
- What was the original damaged asset and what substitute was assigned?
- Did the reassignment succeed, or was it force-cancelled because all alternatives were busy?
- Which algorithmic strategy made the choice (`SameModelStrategy` vs `SameCategoryStrategy`)?

This endpoint serves as the direct backend data source for Stefania's frontend **Reassignment Log** view.

---

## Part 2: Technical Details

### 1. Architecture & Security

- **Base Path:** `/api/v1/admin/reassignments`
- **Role Requirement:** `ROLE_ADMIN`
- **Filter Protection:** Inherited via `SecurityConfig` (`.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")`). Unauthenticated calls return `401 Unauthorized`; student calls return `403 Forbidden`.

### 2. Request & Response Format

- **Query Parameters:**
  - `page` (integer, default `0`): 0-indexed page number.
  - `size` (integer, default `20`): page size.
- **Response Shape:** Standard `PageResponse<ReassignmentLogResponse>`
  ```json
  {
    "content": [
      {
        "id": 1,
        "incidentId": 888,
        "reservationId": 42,
        "fromAssetId": 501,
        "toAssetId": 502,
        "outcome": "REASSIGNED",
        "strategy": "SameModelStrategy",
        "createdAt": "2026-11-04T12:00:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
  ```

### 3. Layer Breakdown

1. **Repository:** `ReassignmentLogRepository.findAllByOrderByCreatedAtDesc(Pageable pageable)` executes an index-backed `ORDER BY created_at DESC` query.
2. **Service:** `ReassignmentService.getLogs(int page, int size)` marked `@Transactional(readOnly = true)`. Maps entities to `ReassignmentLogResponse` records inside transaction boundaries, adhering to `open-in-view=false`.
3. **Controller:** `AdminReassignmentController` handles web layer mapping, OpenAPI annotations, and delegating to the service layer.

### 4. Test Verification Matrix

| Test Suite | Method | Verification |
|---|---|---|
| `AdminReassignmentControllerTest` | `getLogsUnauthenticatedReturns401` | Asserts anonymous request receives HTTP 401 |
| `AdminReassignmentControllerTest` | `getLogsForbiddenForStudent` | Asserts authenticated `STUDENT` receives HTTP 403 |
| `AdminReassignmentControllerTest` | `getLogsSuccessForAdmin` | Asserts authenticated `ADMIN` receives HTTP 200 with JSON pagination |
| `ReassignmentServiceTest` | `getLogsReturnsPaginatedResponse` | Asserts entity-to-DTO mapping and pagination metadata correctness |

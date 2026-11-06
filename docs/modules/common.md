# Common Module & Shared Services (`com.campus.common`, `com.campus.storage`, `com.campus.notification`)

**Owner:** Liviu Nedelcu ([@NliviuN](https://github.com/NliviuN))  
**Base Packages:**
- `com.campus.common.error` — RFC 9457 unified exception handling
- `com.campus.common.paging` — API pagination envelope (`PageResponse<T>`)
- `com.campus.common.audit` — Persistent database audit logging (`AuditService`)
- `com.campus.storage` — Pluggable file storage abstractions (Local vs AWS S3)
- `com.campus.notification` — Pluggable notification abstractions (Logger vs AWS SES)

---

## 1. Purpose

The `common`, `storage`, and `notification` packages provide the shared foundational abstractions utilized across all modular monolith modules (`catalog`, `reservation`, `incident`, `reassignment`, `user`):

1. **RFC 9457 Error Architecture:** Guarantees uniform HTTP problem details and error `code` contracts across the entire REST API.
2. **Standardized Paging:** Decouples Spring Data internal classes from public HTTP contracts via `PageResponse<T>`.
3. **Audit Trail:** Captures compliance and security events in PostgreSQL (`audit_logs`) without coupling callers to database schemas.
4. **Adapter Pattern for Infrastructure:** Decouples business logic from external cloud providers (AWS S3, SES) by supplying local disk and logging implementations for development and automated testing.

---

## 2. Shared Error Handling (`com.campus.common.error`)

### Core Architectural Rules

- **Controllers MUST NEVER build error responses manually:** Never return `ResponseEntity.status(404).body(...)` or catch exceptions inside `@RestController` methods.
- **Throw typed business exceptions:** Service methods throw exceptions from `common.error`. `GlobalExceptionHandler` intercepts them and constructs RFC 9457 `ProblemDetail` payloads.
- **Ownership & IDOR Protection:** When a user queries or modifies a resource that belongs to someone else, services **MUST throw `NotFoundException` (404), NEVER `ForbiddenException` (403)**. This prevents resource enumeration attacks.

### Exception Hierarchy

| Exception | HTTP Status | Typical Error Code (`code`) | Usage / When to Throw |
|---|---|---|---|
| `NotFoundException` | `404 Not Found` | `ASSET_NOT_FOUND`, `RESERVATION_NOT_FOUND`, `INCIDENT_NOT_FOUND`, `CATEGORY_NOT_FOUND` | Queried entity does not exist, or belongs to another user. |
| `ConflictException` | `409 Conflict` | `SERIAL_NUMBER_TAKEN`, `CATEGORY_NAME_TAKEN`, `RESERVATION_CONFLICT`, `ASSET_RETIRED`, `CATEGORY_IN_USE` | Unique constraint violation, overlapping booking, invalid state transition. |
| `BadRequestException` | `400 Bad Request` | `INVALID_PARAMETER`, `MALFORMED_REQUEST`, `INVALID_PHOTO_TYPE` | Missing required parameters, unparseable input, corrupted upload. |
| `PayloadTooLargeException` | `413 Payload Too Large`| `PHOTO_TOO_LARGE` | Uploaded file exceeds application limit (5 MB). |
| `ForbiddenException` | `403 Forbidden` | `FORBIDDEN` | Authenticated user lacks permission (e.g. non-admin accessing `/api/v1/admin/**`). |
| `InvalidStateTransitionException` | `409 Conflict` | `INVALID_RESERVATION_STATUS_TRANSITION`, `INVALID_ASSET_STATUS_TRANSITION` | Entity cannot move from current status to requested status. |
| `BusinessRuleException` | `409 Conflict` | `BOOKING_TOO_FAR_AHEAD`, `OUTSIDE_OPENING_HOURS`, `RESERVATION_LIMIT_REACHED` | Base class for domain-specific business rule violations. |

### Framework Error Handling

`GlobalExceptionHandler` also intercepts framework-level Spring exceptions:
- **`MethodArgumentNotValidException` (`@Valid` in request bodies):** Returns `400 VALIDATION_FAILED` containing field names and rejection reasons.
- **`HandlerMethodValidationException` (`@Validated` on query parameters):** Returns `400 VALIDATION_FAILED` with parameter constraint violations.
- **`OptimisticLockingFailureException` (`@Version` conflicts):** Returns `409 STALE_VERSION` when concurrent modifications conflict.
- **`PropertyReferenceException` (Invalid sort column):** Returns `400 INVALID_PARAMETER` when callers request non-whitelisted sorting.
- **`MaxUploadSizeExceededException`:** Returns `413 PHOTO_TOO_LARGE`.

### RFC 9457 ProblemDetail Payload Example

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "An asset with serial number RIGOL-DS1054Z-001 already exists.",
  "instance": "/api/v1/admin/assets",
  "code": "SERIAL_NUMBER_TAKEN"
}
```

---

## 3. Standardized Pagination (`com.campus.common.paging`)

### Rule: Never Return Spring's `Page<T>` in Controllers

Returning Spring Data's `Page<T>` leaks database internals (e.g. `sort.unsorted`, `pageable` objects). All paginated endpoints return `PageResponse<T>`:

```java
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> springPage) {
        return new PageResponse<>(
            springPage.getContent(),
            springPage.getNumber(),
            springPage.getSize(),
            springPage.getTotalElements(),
            springPage.getTotalPages()
        );
    }
}
```

### Usage Pattern in Services & Controllers

```java
// Inside @Transactional(readOnly = true) Service:
Page<Asset> entityPage = assetRepository.findAll(spec, pageable);
return PageResponse.from(entityPage.map(this::toResponse));
```

Query parameters follow Spring conventions: `?page=0&size=20&sort=name,asc`.

---

## 4. Persistent Database Audit Service (`com.campus.common.audit`)

### Contract: `AuditService`

Captures administrative and security-relevant actions into the PostgreSQL `audit_logs` table:

```java
public interface AuditService {
    void record(String action, String entityType, Long entityId, Long actorId, String details);
}
```

### Parameters & Conventions

- `action`: Uppercase verb (`CREATE_ASSET`, `UPDATE_ASSET`, `RETIRE_ASSET`, `CHECK_OUT`, `RETURN`, `REPORT_INCIDENT`, `AUTO_REASSIGN`).
- `entityType`: The entity class or domain name (`Asset`, `Category`, `LabRoom`, `Reservation`, `IncidentReport`).
- `entityId`: Numeric ID of the modified entity.
- `actorId`: User ID performing the action (obtained from `CurrentUser.id()`). For system events, use `null`.
- `details`: Plain human-readable summary of what changed (e.g. `status changed to UNDER_MAINTENANCE`).

### Fault Tolerance & Isolation

`DatabaseAuditService` captures database exceptions during audit insertion and logs them at `ERROR` level without rolling back the primary transaction. An audit log failure does not break the business flow.

---

## 5. Storage Service (`com.campus.storage`)

Provides photo attachment storage for incident reports:

```java
public interface StorageService {
    String store(byte[] content, String originalFilename, String contentType);
    byte[] load(String key);
    void delete(String key);
}
```

### Profile-Based Adapters

| Profile | Implementation | Target Storage | Security & Validations |
|---|---|---|---|
| `@Profile("!prod")` (Default / Dev / CI) | `LocalStorageService` | Local filesystem (`uploads/photos/`) | Magic bytes check (`JPEG`, `PNG`, `WEBP`), bounded stream reads (max 5 MB), path-traversal prevention (`..`). |
| `@Profile("prod")` (AWS) | `S3StorageService` | AWS S3 Bucket (`campus-hub-photos-*`) | Managed by Matei Necula via AWS SDK v2, authenticated via EC2 IAM instance roles. |

---

## 6. Notification Service (`com.campus.notification`)

Dispatches incident notifications and reservation change alerts:

```java
public interface NotificationService {
    void send(Long recipientUserId, String subject, String body);
}
```

### Profile-Based Adapters

| Profile | Implementation | Mechanism |
|---|---|---|
| `@Profile("!prod")` (Default / Dev / CI) | `LoggingNotificationService` | Emits structured SLF4J logs at `INFO` level. No external network dependencies. |
| `@Profile("prod")` (AWS) | `SesNotificationService` | Managed by Matei Necula via AWS SES (Simple Email Service). |

---

## 7. How to Test

```bash
cd backend

# Run all common, storage, and notification unit/slice tests:
./gradlew test --tests "com.campus.common.*"
./gradlew test --tests "com.campus.storage.*"
```

- `GlobalExceptionHandlerTest`: `@WebMvcTest` asserting RFC 9457 status codes, `code` fields, and validation problem formatting.
- `DatabaseAuditServiceTest`: `@DataJpaTest` against local PostgreSQL verifying audit record insertion.
- `LocalStorageServiceTest`: Validates magic-byte detection, path traversal rejection, and size boundaries.

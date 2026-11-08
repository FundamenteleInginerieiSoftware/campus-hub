# 11 — Administrative Audit Log Endpoint (`GET /api/v1/admin/audit-logs`)

**Author:** Nedelcu Liviu (`@NliviuN`)  
**Area:** Shared Services (`com.campus.common.audit`)  
**Context:** Milestones W7–W8 from `docs/whats-left.md`  

---

## Part 1: In simple terms (pe înțelesul tuturor)

### Ce este și ce problemă rezolvă?
În cadrul aplicației Campus Hub, operațiunile sensibile (dezactivarea unui utilizator, retragerea din uz a unui echipament de laborator defect, raportarea și trierea unui incident, reatribuirea automată a unei rezervări) sunt înregistrate într-un jurnal de audit (`audit_logs`). 

Până în acest moment, serviciul `DatabaseAuditService` doar scria aceste evenimente în baza de date PostgreSQL. Pentru ca administratorii sistemului să poată inspecta istoricul, investiga cine și când a modificat un echipament sau un cont, am adăugat **endpoint-ul de căutare și filtrare a jurnalului de audit**.

### Cine are acces și cum funcționează?
- **Doar administratorii (ADMIN)** pot interoga acest endpoint. Orice apel anonim este respins cu `401 Unauthorized`, iar încercările studenților primesc `403 Forbidden`.
- **Filtrare flexibilă:** Administratorul poate filtra după:
  - Utilizatorul care a făcut acțiunea (`actorId`);
  - Tipul acțiunii (`action`, de ex. `ASSET_RETIRED`, `USER_DISABLED`);
  - Tipul entității (`entityType`, de ex. `ASSET`, `USER`, `INCIDENT`, `RESERVATION`);
  - ID-ul entității specifice (`entityId`);
  - Intervalul de timp (`from` și `to`).
- **Paginare și sortare:** Rezultatele sunt returnate ordonat, cel mai recent eveniment fiind afișat primul (`createdAt,desc`), fără a încărca excesiv memoria serverului.

---

## Part 2: Technical details

### 1. Component Architecture & Endpoints

| Element | Description |
|---|---|
| **Path** | `GET /api/v1/admin/audit-logs` (și alias singular `GET /api/v1/admin/audit-log`) |
| **Security** | RBAC `@RequestMapping("/api/v1/admin/**")` protejat de `SecurityConfig` (`hasRole('ADMIN')`) |
| **Controller** | [`AdminAuditLogController`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/audit/web/AdminAuditLogController.java) |
| **Service Contract** | [`AuditService.search(...)`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/audit/AuditService.java) |
| **Implementation** | [`DatabaseAuditService`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/audit/DatabaseAuditService.java) |
| **Specification** | [`AuditLogSpecification`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/audit/AuditLogSpecification.java) |
| **DTO Response** | [`AuditLogResponse`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/main/java/com/campus/common/audit/web/AuditLogResponse.java) împachetat în `PageResponse<AuditLogResponse>` |

### 2. Query Parameters & Validations

| Parameter | Type | Default | Validation & Constraints |
|---|---|---|---|
| `actorId` | `Long` | `null` | Opțional; identificator numeric al utilizatorului |
| `action` | `String` | `null` | Opțional; max 64 caractere; comparare case-insensitive |
| `entityType` | `String` | `null` | Opțional; max 64 caractere; comparare case-insensitive |
| `entityId` | `Long` | `null` | Opțional; identificator al entității auditate |
| `from` | `LocalDateTime` | `null` | Opțional; format ISO-8601 (`@DateTimeFormat(iso = DATE_TIME)`) |
| `to` | `LocalDateTime` | `null` | Opțional; dacă `from.isAfter(to)` se aruncă `400 INVALID_PARAMETER` |
| `page` | `int` | `0` | Trebuie să fie `≥ 0`; `400 INVALID_PARAMETER` dacă e negativ |
| `size` | `int` | `20` | `1..100`; `400 INVALID_PARAMETER` dacă e în afara intervalului |
| `sort` | `String` | `createdAt,desc` | Whitelist: `createdAt`, `id`, `action`, `entityType`, `actorId`, `entityId`. Include tie-breaker deterministic pe `id`. |

### 3. JPA Criteria Predicates (`AuditLogSpecification`)
Pentru a evita anti-pattern-ul JPQL `(:param IS NULL OR ...)`, care eșuează pe PostgreSQL din cauza lipsei tipului inferat pentru parametrii `null`, am implementat criterii dinamice Spring Data JPA:
- Predicatele sunt adăugate în lista de criterii doar dacă valoarea parametrului este non-nulă și non-goală.
- Căutarea după `action` și `entityType` este normalizată prin `criteriaBuilder.upper(...)`.
- `AuditLogRepository` extinde `JpaSpecificationExecutor<AuditLog>`.

### 4. Deterministic Sort & Tie-Breaking
Paginarea este deterministă chiar și atunci când multiple înregistrări au exact aceeași secundă în timestamp:
```java
Sort.by(direction, field).and(Sort.by(direction, "id"));
```

### 5. Test Suite Verification

| Test Class | Type | Invariants Verified |
|---|---|---|
| [`AuditLogSpecificationTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/common/audit/AuditLogSpecificationTest.java) | `@DataJpaTest` | Filtrare după `actorId`, `action` (case-insensitive), `entityType`, `entityId`, interval de date `[from, to]`, conjuncție multi-filtru. |
| [`DatabaseAuditServiceTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/common/audit/DatabaseAuditServiceTest.java) | Unit Test (Mockito) | Verifică delegarea către repository, maparea entităților în `AuditLogResponse`, validările de limite (`page < 0`, `size 1..100`, `from > to`, sort whitelist, overflow). |
| [`AdminAuditLogControllerTest`](file:///C:/Users/nlivi/Desktop/Campus/campus-hub/backend/src/test/java/com/campus/common/audit/web/AdminAuditLogControllerTest.java) | `@WebMvcTest` | Verifică securitatea (401 anonim, 403 student, 200 admin), ambele rute `/audit-logs` și `/audit-log`, serializarea JSON și maparea erorilor în RFC 9457 `ProblemDetail`. |
| `CodingConventionsArchitectureTest` | ArchUnit | Respectarea regulii Rule 11 (mapare `/api/v1/admin/**`), Rule 2 (fără entități JPA expuse, doar DTO records), Rule 5 (tranzacții doar în service). |

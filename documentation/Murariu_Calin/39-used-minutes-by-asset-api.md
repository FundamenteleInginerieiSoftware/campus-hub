# 39. Real Equipment Usage Minutes API (`ReservationApi.usedMinutesByAsset`, #289)

**Author:** Călin Murariu (@Swiorx)  
**Date:** 2026-11-16  
**Issue:** #289 (Part 2)  
**Pull Request:** `feat/reservation-used-minutes-api`

---

## Part 1: In Simple Terms

### Ce problemă rezolvăm?
Pentru pagina administrativă **Equipment health** („care echipamente se strică cel mai des?”), modulul de incidente al Ștefaniei nu poate judeca fiabilitatea unui aparat doar după numărul brut de incidente. Un osciloscop împrumutat 200 de ore cu 2 defecte este mult mai fiabil decât un aparat similar împrumutat 10 ore cu 2 defecte.

Până acum, modulul de rezervări nu expunea niciun mod de a afla câte minute a fost folosit în realitate fiecare echipament într-un interval de timp (de ex. ultimele 180 de zile).

### Ce am implementat?
Am adăugat o metodă de citire în fațada publică `ReservationApi`:
- **`Map<Long, Long> usedMinutesByAsset(LocalDateTime from, LocalDateTime to)`**
- **Ce numără:** Doar rezervările la care echipamentul a fost ridicat fizic (`checkedOutAt != null`). Rezervările nepreluate (`CONFIRMED`, `CANCELLED`, `FORCE_CANCELLED`, `NO_SHOW`) nu se adună, pentru că aparatul nu a fost folosit.
- **Cum măsoară durata:**
  - Pentru echipamentele returnate: de la `checkedOutAt` până la `returnedAt`.
  - Pentru echipamentele aflate încă la student (`CHECKED_OUT` sau `OVERDUE`): de la `checkedOutAt` până la `min(now, to)`, folosind ceasul comun (`Clock`).
  - Orice utilizare care depășește capetele ferestrei `[from, to]` este tăiată strict la `[from, to]`.
- **Rezultat:** Un dicționar `assetId -> total minute` pentru toate echipamentele cu cel puțin 1 minut de utilizare în fereastră.

---

## Part 2: Technical Details

### 1. Cross-Module Contract (`ReservationApi.java`)
- Signature added to `com.campus.reservation.api.ReservationApi`:
  ```java
  Map<Long, Long> usedMinutesByAsset(LocalDateTime from, LocalDateTime to);
  ```
- Consumed by Stefania's `ReliabilityService` in `com.campus.incident.service` (allowed by `AGENTS.md` §5 and ArchUnit module boundary rules).
- Validation:
  - `from == null || to == null` → throws `InvalidParameterException("'from' and 'to' must not be null")` (`400 INVALID_PARAMETER`).
  - `!to.isAfter(from)` → returns `Map.of()` immediately without querying PostgreSQL.

### 2. Repository Query (`ReservationRepository.java`)
```sql
select r from Reservation r
where r.checkedOutAt is not null
  and r.checkedOutAt < :to
  and (r.returnedAt is null or r.returnedAt > :from)
```
- **Why `checkedOutAt IS NOT NULL`:** Filters out bookings that were never picked up (`CONFIRMED`, `CANCELLED`, `FORCE_CANCELLED`, `NO_SHOW`).
- **Why `checkedOutAt < :to` and `(returnedAt IS NULL OR returnedAt > :from)`:** Standard half-open overlap condition with `[from, to]`, treating unreturned active checkouts (`CHECKED_OUT`, `OVERDUE`) as open-ended until `min(now, to)`. Boundary-touching rows (`returnedAt == from` or `checkedOutAt == to`) have zero overlap and are excluded in SQL.

### 3. Clipping & Aggregation (`ReservationService.java`)
Annotated `@Override @Transactional(readOnly = true)`:
1. Captures `LocalDateTime now = now();` once from the injected `Clock` (truncated to seconds).
2. For each overlapping reservation:
   - `rawEnd = r.getReturnedAt() != null ? r.getReturnedAt() : now`
   - `effectiveStart = r.getCheckedOutAt().isAfter(from) ? r.getCheckedOutAt() : from`
   - `effectiveEnd = rawEnd.isBefore(to) ? rawEnd : to`
   - If `effectiveEnd.isAfter(effectiveStart)`, computes `Duration.between(effectiveStart, effectiveEnd).toMinutes()` and aggregates positive durations per `r.getAssetId()` via `totals.merge(..., Long::sum)`.

### 4. Tests Added
| Test Class | Tests | What They Verify |
|---|---|---|
| `ReservationRepositoryTest` (`@DataJpaTest` on PostgreSQL) | `findCheckedOutOverlapping_includesReturnedAndActiveOverlappingWindow`, `findCheckedOutOverlapping_excludesUnpickedUpAndOutsideWindow` | Includes returned inside window, edge-crossing (`from`/`to`), active `CHECKED_OUT`/`OVERDUE`; excludes `CONFIRMED`/`CANCELLED`/`NO_SHOW`, abutting boundaries (`returnedAt == from`, `checkedOutAt == to`), and outside rows |
| `ReservationServiceTest` (Mockito + fixed `Clock`) | 6 tests (`usedMinutesByAsset_*`) | Full window, clipping at `from`/`to`, active `CHECKED_OUT`/`OVERDUE` capped at `min(now, to)` (and checkouts after `now` ignored), summing multiple reservations per asset, sub-minute omission, `to <= from` returns empty map without DB query, null bounds throw `InvalidParameterException` |

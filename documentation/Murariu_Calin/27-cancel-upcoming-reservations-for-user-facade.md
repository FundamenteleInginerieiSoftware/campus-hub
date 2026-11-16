# 27. Cancel Upcoming Reservations for User Facade & Admin Endpoint (#229 Part 2)

**Author:** Călin Murariu (@Swiorx)  
**Date:** 2026-11-10  
**Issue:** #229 (Part 2)  
**Pull Request:** `feat/reservation-cancel-disabled-user-bookings`

---

## Part 1: In Simple Terms

### Ce problemă rezolvăm?
Atunci când un administrator suspendă sau dezactivează contul unui student (`POST /api/v1/admin/users/{id}/disable`), studentul nu se mai poate autentifica pe platformă. Cu toate acestea, rezervările viitoare pe care le crease anterior rămâneau în sistem cu statusul `CONFIRMED`.
Acest lucru producea trei efecte negative majore:
1. **Blocarea resurselor de laborator:** Echipamentele rezervate de studentul dezactivat rămâneau ocupate și indisponibile pentru ceilalți studenți până când trecea intervalul orar sau intra sweep-ul automat de no-show (+30 de minute după start).
2. **Declanșarea inutilă a motorului de reatribuire:** Dacă un echipament se defecta, motorul de reasignare încerca să găsească înlocuitor pentru o rezervare a unui student care oricum nu mai avea acces în campus și îi trimitea notificări pe email.
3. **Oferte de transfer orfane:** Dacă studentul respectiv inițiase un transfer de rezervare către un coleg (`ReservationTransfer`), transferul rămânea agățat în stare `PENDING`.

### Ce am implementat?
Am adăugat o metodă dedicată în fațada publică `ReservationApi` și un endpoint administrativ în `AdminReservationController`:
- **Fațadă Java:** `int cancelUpcomingReservationsForUser(Long userId, String reason)` disponibilă pentru invocare inter-module sau joburi de mentenanță.
- **Endpoint HTTP REST:** `POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming?reason=...` protejat pentru rolul `ADMIN`.
- **Efecte automate:**
  - Anulează toate rezervările viitoare cu status `CONFIRMED` ale utilizatorului respectiv.
  - Anulează automat orice ofertă de transfer `PENDING` asociată acelor rezervări, notificând colegul că transferul nu mai este valabil.
  - Înregistrează fiecare anulare în jurnalul de audit (`CANCEL_RESERVATION`).
  - Trimite notificări pe email utilizatorului după comiterea cu succes a tranzacției (`afterCommit`) — câte o notificare grupată per `bundleId` (`Reservation Bundle Cancelled`) pentru rezervările bundle și câte una per rezervare individuală.
  - **Important:**
    - Echipamentele deja ridicate fizic (`CHECKED_OUT` sau `OVERDUE`) nu sunt anulate automat, deoarece ele se află în posesia fizică a studentului și trebuie predate fizic la biroul de laborator de către administrator prin procedura standard de returnare (`POST /api/v1/admin/reservations/{id}/return`).
    - Hold-urile temporare active de 1 minut (`ReservationHold`) expiră automat la termen fără intervenție, iar ofertele de transfer primite (`PENDING`) unde utilizatorul dezactivat este destinatar rămân `PENDING` până la expirare sau anulare (acceptarea lor este oricum blocată de contul dezactivat).

---

## Part 2: Technical Details

### 1. Architectural Alignment & Boundaries
Conform regulilor din `AGENTS.md` și ArchUnit (`ModuleBoundaryArchitectureTest`):
- `com.campus.reservation` depinde de `com.campus.user.api` (pentru roluri și verificare existență utilizator prin `userApi.exists(userId)`).
- Păstrăm decuplarea strictă fără cicluri bidirecționale (`no_cycles_between_modules`): modulul `user` **nu trebuie să importe** `reservation` (nici `ReservationApi`). Prin urmare, integrarea din `#229` va fi legată printr-un eveniment `UserDisabledEvent` publicat din `user.api` și consumat sincron printr-un `@EventListener` în modulul `reservation` (care va invoca `cancelUpcomingReservationsForUser`).
- La începutul execuției, `cancelUpcomingReservationsForUser` validează lungimea motivului (`<= 255` caractere), verifică existența utilizatorului (`404 USER_NOT_FOUND` dacă lipsește) și achiziționează blocarea `reservationRepository.lockUserQuota(userId)` pentru a serializa anularea față de eventuale rezervări/hold-uri/transferuri concurente.

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Lab Administrator
    participant AdminCtrl as AdminReservationController
    participant ResService as ReservationService
    participant ResRepo as ReservationRepository
    participant TxSync as TransactionSynchronizationManager
    participant Audit as AuditService
    participant Notif as NotificationService

    Admin->>AdminCtrl: POST /api/v1/admin/reservations/users/{id}/cancel-upcoming
    AdminCtrl->>ResService: cancelUpcomingReservationsForUser(userId, reason)
    ResService->>ResRepo: findUpcomingConfirmedByUser(userId, now)
    ResRepo-->>ResService: List<Reservation> (CONFIRMED & endTime > now)
    
    loop For Each Upcoming Reservation
        ResService->>ResService: reservation.cancel(now, reason)
        ResService->>ResService: cancelPendingTransferIfExists()
        ResService->>Audit: record(actorId, "CANCEL_RESERVATION", "Reservation", id, reason)
        ResService->>TxSync: registerSynchronization(afterCommit -> notif)
    end
    
    ResService->>ResRepo: saveAll(upcoming)
    ResService-->>AdminCtrl: cancelledCount
    AdminCtrl-->>Admin: 200 OK BatchCancelResponse(count, message)
    
    Note over TxSync,Notif: On Transaction Commit (afterCommit)
    TxSync->>Notif: notifyUser(userId, "Reservation Cancelled", ...)
```

### 2. Contract Updates
#### `ReservationApi.java`
```java
/**
 * Cancels all upcoming CONFIRMED reservations for a specific user (e.g. when an administrator
 * disables their account or revokes lab privileges; #229 Part 2).
 *
 * <p>Also automatically cancels any pending outgoing transfer offers for those reservations.
 *
 * @param userId the ID of the user whose upcoming bookings should be cancelled
 * @param reason explanation for cancellation (e.g. "User account disabled by administrator")
 * @return the number of upcoming reservations that were cancelled
 */
int cancelUpcomingReservationsForUser(Long userId, String reason);
```

#### `AdminReservationController.java`
- `POST /api/v1/admin/reservations/users/{userId}/cancel-upcoming`
- Query Params: `reason` (optional, max 255 chars, default: `"Account disabled by administrator"`)
- Errors: `400 VALIDATION_FAILED` (dacă `reason` depășește 255 de caractere), `404 USER_NOT_FOUND` (dacă `userId` nu există), `401 UNAUTHORIZED`, `403 FORBIDDEN`
- Response: `200 OK`
```json
{
  "count": 2,
  "message": "Successfully cancelled 2 upcoming reservation(s)"
}
```

### 3. Database Query
Optimizat prin interogare directă indexată pe `userId` și `endTime`:
```sql
SELECT r FROM Reservation r
WHERE r.userId = :userId
  AND r.status = com.campus.reservation.api.ReservationStatus.CONFIRMED
  AND r.endTime > :cutoff
ORDER BY r.startTime ASC
```

### 4. Test Coverage & Verification Plan
- **Unit Tests (`ReservationServiceTest`):**
  - Anulează toate rezervările viitoare `CONFIRMED` ale utilizatorului după achiziționarea `lockUserQuota(userId)`.
  - Anulează ofertele de transfer `PENDING` asociate și notifică destinatarul transferului după commit.
  - Înregistrează audit (`CANCEL_RESERVATION`) și emite notificări către utilizator exclusiv după comiterea tranzacției (`afterCommit`).
  - Tratează cazul în care utilizatorul nu are rezervări viitoare (returnează 0).
  - Tratează cazul `userId == null` (returnează 0).
  - Folosește motivul implicit atunci când parametrul `reason` este null sau blank.
  - Respinge `reason` > 255 caractere cu `InvalidParameterException` (`INVALID_PARAMETER`).
  - Aruncă `NotFoundException` (`USER_NOT_FOUND`) când `userApi.exists(userId)` este fals.
- **Repository Integration Test (`ReservationRepositoryTest`):**
  - Verifică filtrarea corectă pe bază de date reală PostgreSQL pentru `findUpcomingConfirmedByUser` (excluzând trecutul, alți utilizatori și statusurile `CHECKED_OUT`, `OVERDUE`, `CANCELLED`).
- **Controller Test (`AdminReservationControllerTest`):**
  - Returnează `200 OK` cu `BatchCancelResponse` pentru `ROLE_ADMIN`.
  - Returnează `400 VALIDATION_FAILED` când `reason` depășește 255 caractere.
  - Returnează `404 USER_NOT_FOUND` când utilizatorul nu există.
  - Returnează `403 FORBIDDEN` pentru `ROLE_STUDENT`.
  - Returnează `401 UNAUTHORIZED` pentru apeluri neautentificate.

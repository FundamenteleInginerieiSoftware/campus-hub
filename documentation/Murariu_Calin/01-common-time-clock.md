# Shared Time Bean: ClockConfig (Murariu Călin)

**Role:** Member 3, Reservations & Auto-Reassignment  
**Date:** 2026-10-25  
**Package:** `com.campus.common.time`  
**Branch / PR:** `clock` (PR #27)  

---

## Part 1: In simple terms

In a booking and equipment management system, time is critical. If a student books a lab microscope between 14:00 and 16:00, the system must know exactly what "now" means, what time zone we are in, and whether the booking is in the past or within opening hours.

### The Problem
If developers use Java's default `LocalDateTime.now()` directly in their code:
1. **Time zone discrepancies:** The local developer machine might be set to Bucharest (`Europe/Bucharest`), CI servers (GitHub Actions) run on UTC, and cloud instances (AWS EC2 / RDS) run on UTC. Code that behaves properly locally could fail in production.
2. **Untestable time:** Testing time-sensitive code becomes difficult or impossible (e.g., "what happens when a reservation expires after 15 minutes of no-show?"). You cannot fast-forward physical time inside a unit test.

### The Solution
We introduced a centralized, shared Spring `@Bean` of type `java.time.Clock`:
- Configured by default to the university's time zone: **`Europe/Bucharest`**.
- Every service injects this `Clock` and asks it for the current time: `LocalDateTime.now(clock)`.
- In unit and integration tests, we can supply a fixed clock (`Clock.fixed(...)`), freezing or traveling in time deterministically without brittle `Thread.sleep()` calls.

---

## Part 2: Technical details

### Files Created

| File | Type | Description |
|---|---|---|
| `backend/src/main/java/com/campus/common/time/ClockConfig.java` | Spring `@Configuration` | Declares and configures the singleton `Clock` bean |
| `backend/src/test/java/com/campus/common/time/ClockConfigTest.java` | JUnit 5 Test | Tests property injection, fallback defaults, and direct invocation |

---

### Component Breakdown

#### `ClockConfig.java`
Located in `com.campus.common.time`.

```java
@Configuration
public class ClockConfig {

    @Bean
    Clock clock(@Value("${app.time-zone:Europe/Bucharest}") String zone) {
        return Clock.system(ZoneId.of(zone));
    }
}
```

#### Functions & Design Choices:

1. **`Clock clock(String zone)`**:
   - **Annotation:** `@Bean` with package-private visibility (Spring Boot idiomatic style; public is not required for Spring configuration beans).
   - **Parameter `@Value("${app.time-zone:Europe/Bucharest}") String zone`:** Reads the configured time zone string from `application.yml` under key `app.time-zone`. If the property is missing, it falls back to the default `"Europe/Bucharest"`.
   - **Return Value:** Returns a system clock bound to the specified `ZoneId` via `Clock.system(ZoneId.of(zone))`.
   - **Thread Safety:** `java.time.Clock` instances are immutable and inherently thread-safe.

---

### Testing Strategy (`ClockConfigTest.java`)

We used Spring Boot's lightweight `ApplicationContextRunner` to verify configuration behavior without loading heavy web or database slices:

1. **`clockUsesDefaultZoneWhenNotConfigured()`**:
   - Boots a minimal test context without property overrides.
   - Verifies that the resolved bean has `clock.getZone().equals(ZoneId.of("Europe/Bucharest"))`.
2. **`clockUsesConfiguredZoneFromProperties()`**:
   - Boots context with `.withPropertyValues("app.time-zone=UTC")`.
   - Asserts that the bean adopts the overridden `ZoneId.of("UTC")`.
3. **`clockDirectInvocation()`**:
   - Direct unit test instantiating `new ClockConfig().clock("Europe/Bucharest")` to ensure pure Java instantiation works without Spring.

---

### How Teammates Use This Bean

| Member | Module | Usage |
|---|---|---|
| **Matei Necula** | `user` / `auth` | Passes `LocalDateTime.now(clock)` to `User` entity creation timestamp (`createdAt`) and JWT token issue timestamps (`iat`). |
| **Liviu Nedelcu** | `catalog` / `audit` | Injects `Clock` in `AuditService` to record exact timestamps for audit entries. |
| **Călin Murariu** | `reservation` / `reassignment` | Injects `Clock` in `ReservationService` to validate opening hours, past bookings, and in `ReassignmentService` to query upcoming reservations from `now`. |
| **Ștefania Vișan** | `incident` | Injects `Clock` to record incident report submission times and calculate SLA thresholds. |

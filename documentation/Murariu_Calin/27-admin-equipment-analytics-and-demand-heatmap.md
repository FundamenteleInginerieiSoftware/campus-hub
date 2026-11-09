# 27. Laboratory Equipment Utilization Analytics, Capacity Bottlenecks, and Demand Heatmap

**Author:** Calin Murariu (@Swiorx)  
**Date:** 2026-11-09  
**Branch:** `feat/reservation-admin-analytics-backend`  
**Role:** Member 3 (Reservations + Reassignment + Test Lead)

---

## Part 1: In simple terms

As the equipment catalog and student reservations grew, lab administrators lacked centralized visibility into how laboratory assets were actually used over time. Questions like *"Which expensive instruments sit idle in the cabinet?"*, *"When are the peak rush hours in the lab?"*, and *"Which equipment models frequently hit 100% capacity?"* required tedious manual log inspections.

To solve this, we designed and built an **Admin Analytics & Demand Intelligence** engine:

1. **Equipment Utilization Analytics:** Aggregates real booking data across active, completed, cancelled, and no-show sessions to compute the laboratory's overall utilization percentage, total booked hours, cancellation rates, and no-show rates. Each piece of equipment gets an individual utilization score, instantly identifying top-utilized vs. idle instruments.
2. **Weekly Booking Demand Heatmap:** A visual 7-day by 12-hour grid (Monday to Sunday, 08:00 to 20:00) that maps reservation density. Administrators can instantly spot rush hours (highlighted in red/orange) versus quiet times (green/blue), either lab-wide or filtered for a specific piece of equipment.
3. **Capacity Bottleneck Detection:** Automatically analyzes simultaneous booking demand across all units of each equipment model using a sweep-line concurrency algorithm. When peak demand saturates 100% of available units, the system flags a bottleneck and recommends the exact number of additional units needed to prevent reservation rejections.

All endpoints are strictly restricted to administrators (`/api/v1/admin/analytics/**`), keeping student operational access clean and secure.

---

## Part 2: Technical details

### 1. Architectural Design & Boundaries

- **Module:** `com.campus.reservation` (service: `ReservationAnalyticsService`, web: `AdminAnalyticsController`, repository: `ReservationRepository`).
- **Encapsulation:** Reads equipment metadata via the public `CatalogApi` facade (`getAsset`), respecting modular monolith boundaries (Rule 1 & Rule 2).
- **Security:** Mapped under `/api/v1/admin/**`, automatically guarded by Spring Security with `hasRole('ADMIN')` (401 for unauthenticated, 403 for students).

### 2. Endpoints & Query Specifications

| Endpoint | Method | Role | Description |
|---|---|---|---|
| `/api/v1/admin/analytics/utilization` | `GET` | `ADMIN` | Calculates aggregate utilization %, total booked hours, cancellation/no-show rates, per-equipment utilization list, and capacity bottleneck alerts. |
| `/api/v1/admin/analytics/heatmap` | `GET` | `ADMIN` | Computes a 7x12 (84 cells) weekly demand intensity matrix (08:00–20:00), optionally filtered by `assetId`. |

- **Timezone Safety (AGENTS §5):** Both endpoints accept optional `from` and `to` parameters using `@DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")`. Any timestamp containing explicit timezone offsets (`Z` or `+05:00`) is rejected with HTTP `400 Bad Request` and ProblemDetail code `INVALID_PARAMETER`.
- **Validation:** If `from` is after `to`, the service throws `InvalidParameterException("from", "from must not be after to")`, converted to `400 INVALID_PARAMETER`.

### 3. Algorithms & Mathematical Formulas

- **Operating Hours Base:** Campus labs operate 12 hours/day (08:00 to 20:00).
- **Per-Asset Utilization:**
  $$\text{Utilization} = \min\left(100.0, \frac{\text{Realized Booked Hours}}{\text{Days} \times 12\text{h}} \times 100\right)$$
- **Cancellation & No-Show Rates:**
  $$\text{Cancellation Rate} = \frac{\text{CANCELLED} + \text{FORCE\_CANCELLED}}{\text{Total Intersecting Bookings}} \times 100$$
  $$\text{No-Show Rate} = \frac{\text{NO\_SHOW}}{\text{Total Intersecting Bookings}} \times 100$$
- **Sweep-Line Capacity Bottlenecks:**
  - Evaluates concurrency over time points `(+1 at startTime, -1 at endTime)` for all units sharing a model.
  - If $\text{PeakDemand} \ge \text{TotalUnits}$, computes required surplus: $\text{Deficit} = \text{PeakDemand} - \text{TotalUnits} + 1$.
- **Heatmap Cell Normalization:**
  $$\text{IntensityScore} = \frac{\text{Cell Booking Count}}{\max(\text{All Cell Counts})}$$

### 4. Verification & Testing

- Unit tests in `ReservationAnalyticsServiceTest`:
  - `getUtilization_calculatesMetricsCorrectly`
  - `getUtilization_identifiesCapacityBottlenecks`
  - `getUtilization_rejectsWhenFromAfterTo`
  - `getHeatmap_populatesGridWithCorrectIntensity`
  - `getHeatmap_filtersByAssetId`
- Controller tests in `AdminAnalyticsControllerTest`:
  - 401 unauthenticated, 403 student, 200 admin
  - 400 rejection on timezone offsets (`Z`, numeric)
  - 400 rejection on `from > to`

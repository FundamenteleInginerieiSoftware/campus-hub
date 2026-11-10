# 28. Equipment Utilization Analytics & Demand Heatmap UI

This document details the frontend implementation for the Admin Equipment Utilization Analytics, Booking Demand Heatmap, and Resource Capacity Planning interface in `frontend/src/pages/AdminAnalyticsPage.tsx`.

---

## Part 1: In simple terms

### What is this?
An interactive, graphical dashboard exclusively for lab administrators. It gives administrators full visibility into how campus equipment is being used, where booking demand is concentrated throughout the week, and when hardware shortages risk blocking students.

### Why do we need it?
1. **Equipment Utilization Tracking**: Administrators need to know if expensive lab assets (oscilloscopes, spectrum analyzers, signal generators) are heavily utilized or sitting idle for weeks.
2. **Weekly Demand Heatmap**: A visual Mon-Fri x 12-hour matrix (08:00 to 20:00; weekends hidden because capacity counts only Mon-Fri) highlights peak hours (e.g., Tuesday afternoons) versus quiet mornings, helping lab managers adjust staffing and lab opening schedules.
3. **Resource Capacity Planning**: Automatically alerts the administrator when student demand for a specific hardware model exceeds available units, calculating how many additional units the lab should acquire.

### Key capabilities
- **Preset Time Windows**: Quick toggles for Last 7 Days, Last 30 Days, and Last 90 Days.
- **Top KPI Cards**: Overall lab utilization percentage, total realized booked hours vs open lab capacity, cancellation and no-show loss rates, and count of idle equipment units.
- **Dynamic Demand Heatmap**: Shaded matrix with color-coded demand intensity (from neutral for 0 bookings up to vibrant red for peak hourly slots), with tooltips detailing day, exact hour slot, booking count, and percentage intensity.
- **CSV export**: "Export CSV" downloads the per-asset rows (all, or only the selected asset) as `utilization_<from>_<to>.csv`, built in the browser.
- **Equipment Drill-down & Filter**: Inspect the entire lab or filter the heatmap for an individual asset.
- **Capacity Bottleneck Warnings**: Dedicated callout displaying peak concurrent requests, model saturation rate, and specific acquisition recommendations.

---

## Part 2: Technical details

### Component Architecture

```
frontend/src/
  ├── api/
  │    └── analytics.ts           # TypeScript interfaces, fetch calls, formatLocalIsoDateTime helper
  ├── pages/
  │    └── AdminAnalyticsPage.tsx  # Mantine dashboard, KPI cards, heatmap grid, utilization table
  ├── layout/
  │    └── navigation.ts          # Registered in ADMIN_MENU under Operations
  └── App.tsx                     # Protected admin route /admin/analytics
```

### Endpoints Consumed
- `GET /api/v1/admin/analytics/utilization?from=...&to=...`:
  - Returns `UtilizationAnalyticsResponse` (lab utilization %, capacity hours, idle assets count, per-asset breakdown, detected bottlenecks).
- `GET /api/v1/admin/analytics/heatmap?from=...&to=...&assetId=...`:
  - Returns `HeatmapResponse` (sampled bookings count, peak demand, 84 cells covering days 1..7 and hours 8..19; the page shows only Mon-Fri).

### Campus clock
`getDateWindow` uses `nowInBucharest()` (same helper as the booking pages), so the window is the campus "today" in any browser time zone and ends at "now": future bookings of today are not counted as used hours.

### Client Date Formatting Safety
The backend strictly rejects timezone offsets (`Z`, `+02:00`) with `400 INVALID_PARAMETER` according to `AGENTS.md §5` and bug `#241`. The frontend helper `formatLocalIsoDateTime(date)` produces clean local ISO strings (`"YYYY-MM-DDTHH:mm:ss"`), ensuring seamless compatibility.

### Heatmap Color Scale
| Intensity Range | Cell Background | Text Color | Meaning |
|---|---|---|---|
| `count === 0` | `gray-1` | `dimmed` | Zero reservations |
| `< 0.25` | `blue-1` | Default text | Light demand |
| `0.25 - 0.49` | `blue-3` | Default text | Moderate demand |
| `0.50 - 0.74` | `orange-3` | Default text | Heavy demand |
| `>= 0.75` | `red-5` | `#ffffff` | Peak saturation |

### Verification
- `npm run lint` (`oxlint`): 0 errors, 0 warnings.
- `npm run build` (`tsc -b && vite build`): Succeeded with zero type errors.

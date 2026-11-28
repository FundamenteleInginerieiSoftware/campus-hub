# 58 — Maintenance Impact Simulator Admin Dashboard Page (#58, PR 2)

## Part 1: In simple terms

Added the **Maintenance simulator** page inside the admin dashboard (`/admin/maintenance-simulator`, under Operations in the sidebar). Administrators pick an equipment unit and a planned maintenance window, run the dry-run simulation, and inspect how many upcoming reservations would be auto-reassigned vs. force-cancelled alongside the disruption score.

---

## Part 2: Technical details

| File | Change |
|---|---|
| `frontend/src/api/reassignments.ts` | Added `SimulateMaintenanceRequest`, `SimulatedBookingImpact`, `MaintenanceImpactResponse`, and `simulateMaintenanceImpact`. |
| `frontend/src/pages/MaintenanceSimulatorPage.tsx` | Admin dry-run simulator form (explicit unit selection over up to 100 `AVAILABLE` units, weekday default window skipping Sat/Sun, client-side `end > start` and `<= 90 days` validation, KPI summary cards, and `"Would move"` / `"Would cancel"` per-booking outcome table). |
| `frontend/src/App.tsx`, `frontend/src/layout/navigation.ts` | Registered `/admin/maintenance-simulator` under `<RequireAdmin />` and added the **Maintenance simulator** sidebar link in `ADMIN_MENU`. |

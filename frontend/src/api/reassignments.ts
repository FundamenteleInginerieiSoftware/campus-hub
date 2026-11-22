import { apiFetch } from './client.ts'
import type { PageResponse } from './types.ts'

// Shape of ReassignmentLogResponse.java (Calin's reassignment module, api-contract "Admin reassignments").
// incidentId is null when trigger is RETIRED (an admin retired the asset, #245 / #311).
export type ReassignmentLog = {
  id: number
  incidentId: number | null
  trigger: 'INCIDENT' | 'RETIRED'
  reservationId: number
  fromAssetId: number
  toAssetId: number | null
  outcome: 'REASSIGNED' | 'FORCE_CANCELLED'
  strategy: string | null
  createdAt: string
}

/** Every automatic move or force-cancel, newest first (GET /api/v1/admin/reassignments). */
export function fetchReassignmentLogs(page = 0, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  return apiFetch<PageResponse<ReassignmentLog>>(`/api/v1/admin/reassignments?${params.toString()}`)
}

export type SimulateMaintenanceRequest = {
  assetId: number
  windowStart: string
  windowEnd: string
}

export type SimulatedBookingImpact = {
  reservationId: number
  userId: number
  startTime: string
  endTime: string
  outcome: 'REASSIGNED' | 'FORCE_CANCELLED'
  substituteAssetId: number | null
  substituteAssetName: string | null
  strategy: string | null
}

export type MaintenanceImpactResponse = {
  assetId: number
  assetName: string
  windowStart: string
  windowEnd: string
  totalAffected: number
  reassignedCount: number
  forceCancelledCount: number
  disruptionScore: number
  bookings: SimulatedBookingImpact[]
}

/** Dry-runs planned maintenance impact for an asset (POST /api/v1/admin/reassignments/simulate). */
export function simulateMaintenanceImpact(request: SimulateMaintenanceRequest) {
  return apiFetch<MaintenanceImpactResponse>('/api/v1/admin/reassignments/simulate', {
    method: 'POST',
    body: request,
  })
}

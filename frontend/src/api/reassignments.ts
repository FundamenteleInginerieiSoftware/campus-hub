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

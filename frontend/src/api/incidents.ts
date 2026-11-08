import { apiFetch, apiFetchBlob } from './client.ts'
import type { AssetSummary } from './reservations.ts'
import type { PageResponse } from './types.ts'

// Matching IncidentReport.IncidentStatus / IncidentSeverity in the backend (api-contract, Incidents).
export type IncidentStatus = 'OPEN' | 'IN_REPAIR' | 'RESOLVED' | 'REJECTED'
export type IncidentSeverity = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'

export const SEVERITIES: { value: IncidentSeverity; label: string; hint: string }[] = [
  { value: 'LOW', label: 'Low', hint: 'Cosmetic, it still works' },
  { value: 'MEDIUM', label: 'Medium', hint: 'Works, but not properly' },
  { value: 'HIGH', label: 'High', hint: 'Cannot be used' },
  { value: 'CRITICAL', label: 'Critical', hint: 'Dangerous (smoke, sparks, exposed wires)' },
]

// Shape of IncidentResponse.java. Dates are campus local time without offset: "2026-11-03T14:00:00".
export type Incident = {
  id: number
  assetId: number
  reservationId: number | null
  reportedById: number
  description: string
  severity: IncidentSeverity
  status: IncidentStatus
  photoKey: string | null
  createdAt: string
  resolvedAt: string | null
  resolvedById: number | null
  resolutionNotes: string | null
}

export type CreateIncidentRequest = {
  assetId: number
  description: string
  severity: IncidentSeverity
}

// Same limits as the backend, so the form can say no before sending anything.
export const MAX_DESCRIPTION_LENGTH = 2000
export const MAX_PHOTO_BYTES = 5 * 1024 * 1024
export const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp']

/** The incidents I reported, newest first (GET /api/v1/incidents/me). */
export function fetchMyIncidents(page = 0, size = 10) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  return apiFetch<PageResponse<Incident>>(`/api/v1/incidents/me?${params.toString()}`)
}

/** Reports a problem; the asset goes UNDER_MAINTENANCE at once (POST /api/v1/incidents). */
export function reportIncident(request: CreateIncidentRequest) {
  return apiFetch<Incident>('/api/v1/incidents', { method: 'POST', body: request })
}

/** Adds or replaces the photo (POST /api/v1/incidents/{id}/photo, multipart part "file"). */
export function uploadIncidentPhoto(incidentId: number, file: File) {
  const form = new FormData()
  form.append('file', file)
  return apiFetch<Incident>(`/api/v1/incidents/${incidentId}/photo`, { method: 'POST', body: form })
}

/** The photo as an image file, for the reporter or an admin (GET /api/v1/incidents/{id}/photo). */
export function fetchIncidentPhoto(incidentId: number) {
  return apiFetchBlob(`/api/v1/incidents/${incidentId}/photo`)
}

// ---- admin (Incident queue) ----------------------------------------------------------------

/** Every incident, newest first; status is optional (GET /api/v1/admin/incidents). */
export function fetchAdminIncidents(status: IncidentStatus | null, page = 0, size = 10) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (status) {
    params.set('status', status)
  }
  return apiFetch<PageResponse<Incident>>(`/api/v1/admin/incidents?${params.toString()}`)
}

/** OPEN → IN_REPAIR; the equipment stays out of use (POST /api/v1/admin/incidents/{id}/start-repair). */
export function startRepair(incidentId: number) {
  return apiFetch<Incident>(`/api/v1/admin/incidents/${incidentId}/start-repair`, { method: 'POST' })
}

/**
 * Closes the incident as RESOLVED (fixed) or REJECTED (not a real problem). Notes are optional; without
 * them no body is sent. The equipment is AVAILABLE again once no other open incident is left on it.
 */
export function closeIncident(incidentId: number, outcome: 'resolve' | 'reject', notes: string) {
  const trimmed = notes.trim()
  return apiFetch<Incident>(`/api/v1/admin/incidents/${incidentId}/${outcome}`, {
    method: 'POST',
    body: trimmed ? { resolutionNotes: trimmed } : undefined,
  })
}

/** Equipment to pick in the report form: name, model or serial number contains the text (Liviu's search). */
export function searchAssets(search: string) {
  const params = new URLSearchParams({ size: '20' })
  if (search.trim()) {
    params.set('search', search.trim())
  }
  return apiFetch<PageResponse<AssetSummary>>(`/api/v1/assets?${params.toString()}`)
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

// "2026-11-03T14:05:00" -> "3 Nov 2026, 14:05". Read as text, so the browser's time zone cannot shift it.
export function formatCampusDateTime(value: string | null): string {
  if (!value) {
    return ''
  }
  const [date, time = ''] = value.split('T')
  const [year, month, day] = date.split('-')
  return `${Number(day)} ${MONTHS[Number(month) - 1]} ${year}, ${time.slice(0, 5)}`
}

export function statusColor(status: IncidentStatus): string {
  switch (status) {
    case 'OPEN':
      return 'orange'
    case 'IN_REPAIR':
      return 'blue'
    case 'RESOLVED':
      return 'green'
    case 'REJECTED':
      return 'gray'
  }
}

export function statusLabel(status: IncidentStatus): string {
  switch (status) {
    case 'OPEN':
      return 'Open'
    case 'IN_REPAIR':
      return 'In repair'
    case 'RESOLVED':
      return 'Resolved'
    case 'REJECTED':
      return 'Rejected'
  }
}

export function severityColor(severity: IncidentSeverity): string {
  switch (severity) {
    case 'LOW':
      return 'gray'
    case 'MEDIUM':
      return 'yellow'
    case 'HIGH':
      return 'orange'
    case 'CRITICAL':
      return 'red'
  }
}

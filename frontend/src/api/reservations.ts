import { apiFetch } from './client.ts'
import type { PageResponse } from './types.ts'

// Lifecycle statuses of a reservation, matching ReservationStatus.java in the backend.
export type ReservationStatus =
  | 'CONFIRMED'
  | 'CHECKED_OUT'
  | 'RETURNED'
  | 'CANCELLED'
  | 'FORCE_CANCELLED'
  | 'OVERDUE'
  | 'NO_SHOW'

export const ACTIVE_STATUSES: ReservationStatus[] = ['CONFIRMED', 'CHECKED_OUT', 'OVERDUE']

// Shape matching ReservationResponse.java (api-contract §1 & §4).
export type Reservation = {
  id: number
  userId: number
  assetId: number
  originalAssetId: number | null
  startTime: string // ISO local without offset: "2026-11-03T14:00:00"
  endTime: string // ISO local without offset: "2026-11-03T16:00:00"
  status: ReservationStatus
  createdAt: string
  checkedOutAt: string | null
  returnedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  bundleId?: string | null
}

export type TransferStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED'

export type ReservationTransferResponse = {
  id: number
  reservationId: number
  fromUserId: number
  toUserId: number
  status: TransferStatus
  requestedAt: string
  respondedAt: string | null
}

export type CreateReservationRequest = {
  assetId: number
  startTime: string // "YYYY-MM-DDTHH:mm:ss"
  endTime: string // "YYYY-MM-DDTHH:mm:ss"
}

export type AvailabilityResponse = {
  assetId: number | null
  categoryId: number | null
  startTime: string
  endTime: string
  available: boolean
  availableAssetIds: number[]
  conflictReason: string | null
}

// Minimal equipment details fetched to enrich the reservation view (AssetResponse.java).
export type AssetSummary = {
  id: number
  categoryId: number
  labRoomId: number
  name: string
  model: string
  serialNumber: string
  status: 'AVAILABLE' | 'UNDER_MAINTENANCE' | 'RETIRED'
}

export type ReservationSummary = {
  activeCount: number
  completedCount: number
  reassignedCount: number
}

/**
 * Fetches the aggregate counts for the student's bookings (GET /api/v1/reservations/me/summary).
 */
export function fetchMyReservationSummary() {
  return apiFetch<ReservationSummary>('/api/v1/reservations/me/summary')
}

/**
 * Fetches the paginated reservations belonging to the authenticated student (GET /api/v1/reservations/me).
 * Supports single status or array of statuses for server-side filtering.
 */
export function fetchMyReservations(page = 0, size = 10, status?: ReservationStatus | ReservationStatus[]) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (status) {
    if (Array.isArray(status)) {
      for (const s of status) {
        params.append('status', s)
      }
    } else {
      params.set('status', status)
    }
  }
  return apiFetch<PageResponse<Reservation>>(`/api/v1/reservations/me?${params.toString()}`)
}

/**
 * Fetches reservation details by ID (GET /api/v1/reservations/{id}).
 */
export function fetchReservationById(id: number) {
  return apiFetch<Reservation>(`/api/v1/reservations/${id}`)
}

/**
 * Cancels a confirmed reservation before its slot starts (POST /api/v1/reservations/{id}/cancel).
 */
export function cancelReservation(id: number) {
  return apiFetch<Reservation>(`/api/v1/reservations/${id}/cancel`, { method: 'POST' })
}

/**
 * Books an asset for the authenticated student (POST /api/v1/reservations).
 */
export function createReservation(request: CreateReservationRequest) {
  return apiFetch<Reservation>('/api/v1/reservations', { method: 'POST', body: request })
}

/**
 * Checks if a specific asset or any asset in a category is free in the requested window (GET /api/v1/reservations/availability).
 */
export function checkAvailability(params: {
  assetId?: number
  categoryId?: number
  startTime: string
  endTime: string
}) {
  const search = new URLSearchParams({
    startTime: params.startTime,
    endTime: params.endTime,
  })
  if (params.assetId !== undefined) {
    search.set('assetId', String(params.assetId))
  }
  if (params.categoryId !== undefined) {
    search.set('categoryId', String(params.categoryId))
  }
  return apiFetch<AvailabilityResponse>(`/api/v1/reservations/availability?${search.toString()}`)
}

/**
 * Looks up equipment info (name, model, etc.) to show next to the reservation row (GET /api/v1/assets/{id}).
 */
export function fetchAssetSummary(assetId: number) {
  return apiFetch<AssetSummary>(`/api/v1/assets/${assetId}`)
}

/**
 * Extends the duration of an active reservation (POST /api/v1/reservations/{id}/extend).
 */
export function extendReservation(id: number, newEndTime: string) {
  return apiFetch<Reservation>(`/api/v1/reservations/${id}/extend`, {
    method: 'POST',
    body: { newEndTime },
  })
}

/**
 * Initiates a P2P transfer of an upcoming confirmed reservation (POST /api/v1/reservations/{id}/transfers).
 */
export function initiateTransfer(id: number, recipientUserId: number) {
  return apiFetch<ReservationTransferResponse>(`/api/v1/reservations/${id}/transfers`, {
    method: 'POST',
    body: { recipientUserId },
  })
}

/**
 * Retrieves pending incoming reservation transfer requests for the current student (GET /api/v1/reservations/transfers/pending).
 */
export function fetchPendingTransfers() {
  return apiFetch<ReservationTransferResponse[]>('/api/v1/reservations/transfers/pending')
}

/**
 * Accepts a pending reservation transfer offer (POST /api/v1/reservations/transfers/{transferId}/accept).
 */
export function acceptTransfer(transferId: number) {
  return apiFetch<ReservationTransferResponse>(`/api/v1/reservations/transfers/${transferId}/accept`, {
    method: 'POST',
  })
}

/**
 * Rejects an incoming reservation transfer offer (POST /api/v1/reservations/transfers/{transferId}/reject).
 */
export function rejectTransfer(transferId: number) {
  return apiFetch<ReservationTransferResponse>(`/api/v1/reservations/transfers/${transferId}/reject`, {
    method: 'POST',
  })
}

/**
 * Returns a Date object whose local year, month, day, hour, minute, and second
 * match the current wall-clock time in Europe/Bucharest (the shared campus clock zone).
 */
export function nowInBucharest(): Date {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Europe/Bucharest',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(new Date())

  const get = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find((p) => p.type === type)?.value ?? 0)

  const hour = get('hour') === 24 ? 0 : get('hour')
  return new Date(get('year'), get('month') - 1, get('day'), hour, get('minute'), get('second'))
}

/**
 * Parses an ISO-8601 local timestamp without offset ("YYYY-MM-DDTHH:mm:ss" or "YYYY-MM-DD")
 * into a Date with matching year/month/day/hour/minute/second, avoiding UTC date-only parsing skew.
 */
export function parseCampusLocalDateTime(isoString: string): Date {
  const [datePart, timePart = '00:00:00'] = isoString.split('T')
  const [y, m, d] = datePart.split('-').map(Number)
  const [hh = 0, mm = 0, ss = 0] = timePart.split(':').map(Number)
  return new Date(y, m - 1, d, hh, mm, ss)
}

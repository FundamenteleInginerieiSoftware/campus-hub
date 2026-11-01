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

/**
 * Fetches the paginated reservations belonging to the authenticated student (GET /api/v1/reservations/me).
 */
export function fetchMyReservations(page = 0, size = 10, status?: ReservationStatus) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (status) {
    params.set('status', status)
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

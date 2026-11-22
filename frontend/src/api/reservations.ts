import { ApiError, apiFetch, tokenStorage } from './client.ts'
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

export type HoldStatus = 'ACTIVE' | 'CONFIRMED' | 'RELEASED' | 'EXPIRED'

export type ReservationHoldResponse = {
  id: number
  assetId: number
  userId: number
  startTime: string
  endTime: string
  expiresAt: string
  status: HoldStatus
  createdAt: string
  remainingSeconds: number
}

export type CreateHoldRequest = {
  assetId: number
  startTime: string
  endTime: string
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
 * Cancels all remaining confirmed reservations in a multi-asset bundle (POST /api/v1/reservations/bundle/{bundleId}/cancel).
 */
export function cancelBundle(bundleId: string) {
  return apiFetch<Reservation[]>(`/api/v1/reservations/bundle/${encodeURIComponent(bundleId)}/cancel`, {
    method: 'POST',
  })
}

// Remembers the Idempotency-Key of the latest unfinished booking attempt for a given
// (assetId, startTime, endTime) slot so a retry or double-click reuses the exact same key,
// while selecting a different slot or starting a new booking after success generates a fresh key.
let pendingBookingAttempt: { slotSignature: string; key: string } | null = null

export function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `idem-${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

/**
 * Books an asset for the authenticated student (POST /api/v1/reservations).
 * Automatically sends and reuses a slot-scoped Idempotency-Key header so network
 * retries or double-clicks on the same slot do not create duplicate reservations.
 */
export async function createReservation(
  request: CreateReservationRequest,
  idempotencyKey?: string,
): Promise<Reservation> {
  const slotSignature = `${request.assetId}|${request.startTime}|${request.endTime}`
  let key: string
  if (idempotencyKey !== undefined) {
    key = idempotencyKey
  } else if (pendingBookingAttempt?.slotSignature === slotSignature) {
    key = pendingBookingAttempt.key
  } else {
    key = generateIdempotencyKey()
    pendingBookingAttempt = { slotSignature, key }
  }

  const token = tokenStorage.get()
  const headers: Record<string, string> = {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    'Idempotency-Key': key,
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const response = await fetch('/api/v1/reservations', {
    method: 'POST',
    headers,
    body: JSON.stringify(request),
  })

  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    if ((response.status === 400 || response.status === 401) && pendingBookingAttempt?.key === key) {
      pendingBookingAttempt = null
    }
    if (response.status === 401 && token) {
      tokenStorage.clear()
    }
    throw new ApiError(
      response.status,
      problem?.code ?? 'UNKNOWN_ERROR',
      problem?.detail ?? `Request failed with status ${response.status}`,
      problem?.errors ?? [],
    )
  }

  if (pendingBookingAttempt?.key === key) {
    pendingBookingAttempt = null
  }
  return (await response.json()) as Reservation
}

/**
 * Acquires a temporary 1-minute slot hold on an asset (POST /api/v1/reservations/holds).
 */
export function createHold(request: CreateHoldRequest) {
  return apiFetch<ReservationHoldResponse>('/api/v1/reservations/holds', {
    method: 'POST',
    body: request,
  })
}

/**
 * Confirms an active slot hold into a confirmed reservation (POST /api/v1/reservations/holds/{id}/confirm).
 */
export function confirmHold(holdId: number) {
  return apiFetch<Reservation>(`/api/v1/reservations/holds/${holdId}/confirm`, {
    method: 'POST',
  })
}

/**
 * Releases an active slot hold early (POST /api/v1/reservations/holds/{id}/release).
 */
export function releaseHold(holdId: number) {
  return apiFetch<ReservationHoldResponse>(`/api/v1/reservations/holds/${holdId}/release`, {
    method: 'POST',
  })
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

export type FreeSlotOption = {
  startTime: string
  endTime: string
}

export type NextFreeSlotsResponse = {
  assetId: number
  durationMinutes: number
  bookable: boolean
  slots: FreeSlotOption[]
}

/**
 * Recommends the earliest upcoming free slots for an asset (GET /api/v1/reservations/next-free-slots, #61).
 */
export function fetchNextFreeSlots(
  assetId: number,
  from: string,
  durationMinutes: number,
  limit = 4,
) {
  const params = new URLSearchParams({
    assetId: String(assetId),
    from,
    durationMinutes: String(durationMinutes),
    limit: String(limit),
  })
  return apiFetch<NextFreeSlotsResponse>(`/api/v1/reservations/next-free-slots?${params.toString()}`)
}

export type WaitlistStatus = 'WAITING' | 'PROMOTED' | 'CANCELLED' | 'EXPIRED' | 'SKIPPED_QUOTA_FULL'

export type WaitlistEntry = {
  id: number
  assetId: number
  userId: number
  startTime: string
  endTime: string
  status: WaitlistStatus
  promotedReservationId: number | null
  createdAt: string
  updatedAt: string
}

/**
 * Queues the current user onto the FIFO waitlist for an occupied slot (POST /api/v1/reservations/waitlist).
 */
export function joinWaitlist(request: CreateReservationRequest) {
  return apiFetch<WaitlistEntry>('/api/v1/reservations/waitlist', {
    method: 'POST',
    body: request,
  })
}

/**
 * Lists the authenticated user's waitlist entries, newest first (GET /api/v1/reservations/waitlist/me).
 */
export function fetchMyWaitlist(page = 0, size = 20) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  return apiFetch<PageResponse<WaitlistEntry>>(`/api/v1/reservations/waitlist/me?${params.toString()}`)
}

/**
 * Cancels a WAITING waitlist entry (DELETE /api/v1/reservations/waitlist/{id}).
 */
export function cancelWaitlistEntry(id: number) {
  return apiFetch<void>(`/api/v1/reservations/waitlist/${id}`, {
    method: 'DELETE',
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

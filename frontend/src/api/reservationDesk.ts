import { apiFetch } from './client.ts'
import type { Reservation, ReservationStatus } from './reservations.ts'
import type { PageResponse } from './types.ts'

// Admin calls for the Reservations desk page. The endpoints are Calin's AdminReservationController
// (api-contract §3); the reservation types come from his src/api/reservations.ts.

export type DeskSearch = {
  status?: ReservationStatus
  date?: string // "YYYY-MM-DD": bookings that overlap this day
  page?: number
  size?: number
}

/**
 * Searches all students' reservations (GET /api/v1/admin/reservations).
 * The backend takes one status at a time and sorts by start time, newest first.
 */
export function searchReservations({ status, date, page = 0, size = 20 }: DeskSearch) {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (status) params.set('status', status)
  if (date) params.set('date', date)
  return apiFetch<PageResponse<Reservation>>(`/api/v1/admin/reservations?${params.toString()}`)
}

/**
 * Hands the equipment to the student (POST /api/v1/admin/reservations/{id}/check-out).
 * Only for CONFIRMED bookings, from 15 min before to 30 min after the start (409 CHECKOUT_WINDOW_CLOSED).
 */
export function checkOutReservation(id: number) {
  return apiFetch<Reservation>(`/api/v1/admin/reservations/${id}/check-out`, { method: 'POST' })
}

/**
 * Takes the equipment back (POST /api/v1/admin/reservations/{id}/return).
 * Only for CHECKED_OUT or OVERDUE bookings (409 INVALID_RESERVATION_STATUS_TRANSITION otherwise).
 */
export function returnReservation(id: number) {
  return apiFetch<Reservation>(`/api/v1/admin/reservations/${id}/return`, { method: 'POST' })
}

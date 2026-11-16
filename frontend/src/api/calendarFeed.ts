import { apiFetch } from './client.ts'

export type CalendarFeedStatus = {
  enabled: boolean
}

export type CalendarFeedCreated = {
  path: string
}

/**
 * Checks whether the current user has an active calendar subscription link
 * (GET /api/v1/reservations/me/calendar-feed).
 */
export function fetchCalendarFeed(): Promise<CalendarFeedStatus> {
  return apiFetch<CalendarFeedStatus>('/api/v1/reservations/me/calendar-feed')
}

/**
 * Creates or replaces the current user's secret calendar subscription link
 * (POST /api/v1/reservations/me/calendar-feed). The raw token path is returned only once.
 */
export function createCalendarFeed(): Promise<CalendarFeedCreated> {
  return apiFetch<CalendarFeedCreated>('/api/v1/reservations/me/calendar-feed', {
    method: 'POST',
  })
}

/**
 * Revokes the current user's calendar subscription link so it immediately stops working
 * (DELETE /api/v1/reservations/me/calendar-feed).
 */
export function revokeCalendarFeed(): Promise<void> {
  return apiFetch<void>('/api/v1/reservations/me/calendar-feed', {
    method: 'DELETE',
  })
}

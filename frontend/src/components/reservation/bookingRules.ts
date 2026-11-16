// Shared campus reservation rules matching backend BookingRules / ReservationService.
export const OPEN_HOUR = 8
export const CLOSE_HOUR = 20
export const CLOSE_MINUTES = CLOSE_HOUR * 60
export const OPENING_TIME = '08:00'
export const CLOSING_TIME = '20:00'
export const DEFAULT_START_TIME = '09:00'
export const LAST_START_HOUR = 19
export const LAST_START_MINUTE = 30
export const LAST_START_MINUTES = LAST_START_HOUR * 60 + LAST_START_MINUTE

export const SLOT_STEP_MINUTES = 30
export const MAX_ADVANCE_DAYS = 14
export const MAX_DURATION_MINUTES = 240
export const DEFAULT_DURATION_MINUTES = 60
export const MAX_ACTIVE_RESERVATIONS = 2
export const HOLD_DURATION_SECONDS = 60

export const BOOKING_DURATIONS = [
  { label: '30 min', value: 30 },
  { label: '1 h', value: 60 },
  { label: '1.5 h', value: 90 },
  { label: '2 h', value: 120 },
  { label: '3 h', value: 180 },
  { label: '4 h', value: 240 },
] as const

export const EXTENSION_STEP_MINUTES = [30, 60, 90, 120, 150, 180] as const

// 30-min steps from 08:00 to 19:30 (last start before 20:00 closing hour).
export const TIME_STEPS: string[] = Array.from(
  { length: (CLOSE_HOUR - OPEN_HOUR) * 2 },
  (_, index) => {
    const hour = OPEN_HOUR + Math.floor(index / 2)
    const minute = index % 2 === 0 ? '00' : '30'
    return `${String(hour).padStart(2, '0')}:${minute}`
  },
)

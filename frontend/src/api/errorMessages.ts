import { ApiError } from './client.ts'

// Friendly text per backend error code (api-contract §5). Add your module's codes here;
// anything not listed falls back to the backend's own "detail" text.
const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Wrong email or password.',
  UNAUTHORIZED: 'Your session has expired. Please log in again.',
  FORBIDDEN: 'You do not have access to this page.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check them and try again.',
  EMAIL_TAKEN: 'An account with this email already exists.',

  // Reservation error codes (api-contract §5, Calin)
  ASSET_NOT_FOUND: 'The requested equipment was not found.',
  NO_ASSETS_AVAILABLE: 'No free equipment of this type remains in the selected time window.',
  RESERVATION_NOT_FOUND: 'Reservation was not found.',
  RESERVATION_CONFLICT: 'This equipment is already booked for the selected time slot.',
  ASSET_NOT_BOOKABLE: 'This equipment is currently not available for booking (under maintenance or retired).',
  RESERVATION_LIMIT_REACHED: 'You have reached the maximum of 2 active reservations. Please cancel one or wait for it to finish.',
  BOOKING_TOO_FAR_AHEAD: 'Reservations can only be made up to 14 days in advance.',
  BOOKING_IN_PAST: 'Cannot book a time slot in the past.',
  INVALID_TIME_RANGE: 'End time must be after start time.',
  INVALID_DURATION: 'Reservation duration must be between 30 minutes and 4 hours.',
  OUTSIDE_OPENING_HOURS: 'Reservations can only be made Monday to Friday between 08:00 and 20:00.',
  SLOT_NOT_ALIGNED: 'Start and end times must align with half-hour marks (:00 or :30).',
  CANCELLATION_NOT_ALLOWED: 'Reservations can only be cancelled before their start time.',
  CHECKOUT_WINDOW_CLOSED: 'Pick-up window is closed (allowed from 15 min before start to 30 min after start).',
  INVALID_RESERVATION_STATUS_TRANSITION: 'This reservation cannot be transitioned to the requested status.',

  // Catalog error codes (api-contract §5, Liviu)
  CATEGORY_NOT_FOUND: 'The selected category was not found.',
  LAB_ROOM_NOT_FOUND: 'The selected lab room was not found.',
  SERIAL_NUMBER_TAKEN: 'An asset with this serial number already exists.',
  CATEGORY_NAME_TAKEN: 'A category with this name already exists.',
  LAB_ROOM_CODE_TAKEN: 'A lab room with this code already exists.',
  CATEGORY_IN_USE: 'Cannot delete this category because equipment is currently assigned to it.',
  LAB_ROOM_IN_USE: 'Cannot delete this lab room because equipment is currently located in it.',
  ASSET_RETIRED: 'This asset has been retired and can no longer be modified or booked.',
  INVALID_ASSET_STATUS_TRANSITION: 'This equipment cannot transition to the requested status.',
  STALE_VERSION: 'This item was modified by another user. Please refresh and try again.',
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return MESSAGES[error.code] ?? error.message
  }
  return 'Cannot reach the server. Check that the backend is running.'
}

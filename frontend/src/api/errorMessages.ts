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

  // Incident error codes (api-contract §5, Stefania)
  INCIDENT_NOT_FOUND: 'Incident was not found.',
  INVALID_INCIDENT_STATUS_TRANSITION: 'This incident is already closed or cannot change to that status.',
  ASSET_RETIRED: 'This equipment is retired and cannot be reported or booked.',
  PHOTO_NOT_FOUND: 'This incident has no photo.',
  INVALID_PHOTO_TYPE: 'Only JPEG, PNG or WebP images are allowed.',
  PHOTO_TOO_LARGE: 'The photo is too large (max 5 MB).',

  // Generic codes from the error handler (api-contract §5) that pages can hit (#171)
  INVALID_PARAMETER: 'Some values in the request are not valid. Please check them and try again.',
  MALFORMED_REQUEST: 'The request could not be read. Please check the form and try again.',
  NOT_FOUND: 'This page or item does not exist.',
  STALE_VERSION: 'Someone else changed this just now. Please reload and try again.',
  INTERNAL_ERROR: 'Something went wrong on the server. Please try again in a moment.',
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return MESSAGES[error.code] ?? error.message
  }
  return 'Cannot reach the server. Check that the backend is running.'
}

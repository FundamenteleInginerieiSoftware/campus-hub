import { ApiError } from './client.ts'

// Friendly text per backend error code (api-contract §5). Add your module's codes here;
// anything not listed falls back to the backend's own "detail" text.
const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Wrong email or password.',
  UNAUTHORIZED: 'Your session has expired. Please log in again.',
  FORBIDDEN: 'You do not have access to this page.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check them and try again.',
  EMAIL_TAKEN: 'An account with this email already exists.',

  // User admin error codes (api-contract §5, Stefania)
  USER_NOT_FOUND: 'User was not found.',
  CANNOT_MODIFY_SELF: 'You cannot disable your own account or remove your own admin role.',
  LAST_ADMIN_REQUIRED: 'At least one enabled admin must remain.',

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
  EXTENSION_CONFLICT: 'Cannot extend reservation: the requested time slot overlaps with another booking or hold.',
  CANNOT_TRANSFER_TO_SELF: 'You cannot transfer a reservation to yourself.',
  TRANSFER_ALREADY_PENDING: 'A transfer offer for this reservation is already pending.',
  TRANSFER_NOT_FOUND: 'The transfer request was not found or has already expired.',
  RESERVATION_NOT_TRANSFERABLE: 'Only upcoming confirmed reservations can be transferred.',
  SLOT_HELD: 'This time slot is temporarily held by another student.',
  HOLD_EXPIRED: 'Your temporary slot hold has expired. Please select a time slot again.',
  HOLD_NOT_FOUND: 'The slot hold was not found or has already been released.',
  HOLD_LIMIT_REACHED: 'You already have an active slot hold. Please confirm or release it first.',
  ACTIVE_HOLD_EXISTS: 'You already have an active hold on this or another slot.',

  // Incident error codes (api-contract §5, Stefania)
  INCIDENT_NOT_FOUND: 'Incident was not found.',
  INVALID_INCIDENT_STATUS_TRANSITION: 'This incident is already closed or cannot change to that status.',
  ASSET_RETIRED: 'This equipment is retired and cannot be reported or booked.',
  PHOTO_NOT_FOUND: 'This incident has no photo.',
  INVALID_PHOTO_TYPE: 'Only JPEG, PNG or WebP images are allowed.',
  PHOTO_TOO_LARGE: 'The photo is too large (max 5 MB).',

  // Catalog error codes (api-contract §5, Liviu)
  CATEGORY_NOT_FOUND: 'The selected category was not found.',
  LAB_ROOM_NOT_FOUND: 'The selected lab room was not found.',
  SERIAL_NUMBER_TAKEN: 'An asset with this serial number already exists.',
  CATEGORY_NAME_TAKEN: 'A category with this name already exists.',
  LAB_ROOM_CODE_TAKEN: 'A lab room with this code already exists.',
  CATEGORY_IN_USE: 'Cannot delete this category because equipment is currently assigned to it.',
  LAB_ROOM_IN_USE: 'Cannot delete this lab room because equipment is currently located in it.',

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

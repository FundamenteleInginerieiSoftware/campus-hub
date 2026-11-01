import { ApiError } from './client.ts'

// Friendly text per backend error code (api-contract §5). Add your module's codes here;
// anything not listed falls back to the backend's own "detail" text.
const MESSAGES: Record<string, string> = {
  INVALID_CREDENTIALS: 'Wrong email or password.',
  UNAUTHORIZED: 'Your session has expired. Please log in again.',
  FORBIDDEN: 'You do not have access to this page.',
  VALIDATION_FAILED: 'Some fields are not valid. Please check them and try again.',
  EMAIL_TAKEN: 'An account with this email already exists.',
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return MESSAGES[error.code] ?? error.message
  }
  return 'Cannot reach the server. Check that the backend is running.'
}

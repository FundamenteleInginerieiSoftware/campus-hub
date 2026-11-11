import { apiFetch } from './client.ts'

// Calls for the pickup pass (api-contract "Pickup pass"). The backend is Matei's PickupPassService in user/:
// a 6-digit code that changes every 30 seconds, so a screenshot is useless a minute later.

export type PickupPass = {
  // 6 digits, can start with 0, so it stays a string
  code: string
  // What the QR image will hold: "campus-pickup:1:<userId>:<code>"
  qrText: string
  // Seconds left until the code changes (30 down to 1)
  validForSeconds: number
}

export type VerifiedStudent = { userId: number; fullName: string; email: string }

/** GET /api/v1/users/me/pickup-pass: the logged-in student's current code. */
export function fetchPickupPass(): Promise<PickupPass> {
  return apiFetch<PickupPass>('/api/v1/users/me/pickup-pass')
}

/**
 * POST /api/v1/admin/pickup-pass/verify (admin only): 200 with the student's name when the code is right,
 * 409 PICKUP_CODE_INVALID when it is wrong or expired.
 */
export function verifyPickupPass(userId: number, code: string): Promise<VerifiedStudent> {
  return apiFetch<VerifiedStudent>('/api/v1/admin/pickup-pass/verify', { method: 'POST', body: { userId, code } })
}

// Reads a scanned QR text. Only the exact format of the backend's qrText is accepted.
export function parseQrText(text: string): { userId: number; code: string } | null {
  const match = /^campus-pickup:1:(\d+):(\d{6})$/.exec(text)
  return match ? { userId: Number(match[1]), code: match[2] } : null
}

import { apiFetch } from './client.ts'
import type { Role } from './auth.ts'
import type { PageResponse } from './types.ts'

// Shape of AdminUserResponse.java (api-contract, User admin). Never contains the password hash.
export type AdminUser = {
  id: number
  email: string
  fullName: string
  role: Role
  enabled: boolean
  createdAt: string
}

/** One user, for admins (GET /api/v1/admin/users/{id}); used to show who reported an incident. */
export function fetchAdminUser(userId: number) {
  return apiFetch<AdminUser>(`/api/v1/admin/users/${userId}`)
}

/** Admin list, newest first; q matches part of the email or the name (GET /api/v1/admin/users). */
export function searchAdminUsers(params: {
  q?: string
  role?: Role | null
  enabled?: boolean | null
  page?: number
  size?: number
}) {
  const search = new URLSearchParams({ page: String(params.page ?? 0), size: String(params.size ?? 20) })
  if (params.q?.trim()) search.set('q', params.q.trim())
  if (params.role) search.set('role', params.role)
  if (params.enabled !== null && params.enabled !== undefined) search.set('enabled', String(params.enabled))
  return apiFetch<PageResponse<AdminUser>>(`/api/v1/admin/users?${search.toString()}`)
}

/**
 * Disables an account: it can no longer log in, its token stops working on the next request, and its upcoming
 * bookings are cancelled after the commit (#302). 409 CANNOT_MODIFY_SELF / LAST_ADMIN_REQUIRED.
 */
export function disableUser(userId: number) {
  return apiFetch<AdminUser>(`/api/v1/admin/users/${userId}/disable`, { method: 'POST' })
}

export function enableUser(userId: number) {
  return apiFetch<AdminUser>(`/api/v1/admin/users/${userId}/enable`, { method: 'POST' })
}

/** STUDENT ↔ ADMIN; counts from the user's next request. 409 CANNOT_MODIFY_SELF / LAST_ADMIN_REQUIRED. */
export function changeUserRole(userId: number, role: Role) {
  return apiFetch<AdminUser>(`/api/v1/admin/users/${userId}/role`, { method: 'PUT', body: { role } })
}

import { apiFetch } from './client.ts'
import type { Role } from './auth.ts'

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

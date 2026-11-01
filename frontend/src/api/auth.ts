import { apiFetch } from './client.ts'

// Shapes from api-contract §3 (Matei's auth endpoints).
export type Role = 'STUDENT' | 'ADMIN'

export type User = {
  id: number
  email: string
  fullName: string
  role: Role
}

type LoginResponse = {
  accessToken: string
  expiresIn: number
  user: User
}

export function login(email: string, password: string) {
  return apiFetch<LoginResponse>('/api/v1/auth/login', { method: 'POST', body: { email, password } })
}

export function register(email: string, password: string, fullName: string) {
  return apiFetch<User>('/api/v1/auth/register', { method: 'POST', body: { email, password, fullName } })
}

export function fetchMe() {
  return apiFetch<User>('/api/v1/users/me')
}

import { createContext, useContext } from 'react'
import type { User } from '../api/auth.ts'

export type AuthState = {
  user: User | null
  // true only on the first load, while we check whether a saved token is still valid
  loading: boolean
  // Set when that first check could not reach the backend (restarting, network down). The token is kept,
  // because it may still be valid; RequireAuth shows a "try again" screen instead of the login page.
  startupError: unknown
  retry: () => void
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

export const AuthContext = createContext<AuthState | null>(null)

// Any page can call useAuth() to know who is logged in and to log in or out.
export function useAuth(): AuthState {
  const auth = useContext(AuthContext)
  if (!auth) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return auth
}

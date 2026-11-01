import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { fetchMe, login as loginRequest, type User } from '../api/auth.ts'
import { setUnauthorizedHandler, tokenStorage } from '../api/client.ts'
import { AuthContext } from './useAuth.ts'

// Holds the logged-in user for the whole app. On startup, a token saved by an earlier visit is
// checked with GET /api/v1/users/me, so a reload keeps the user logged in.
export default function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(() => tokenStorage.get() !== null)

  const logout = useCallback(() => {
    tokenStorage.clear()
    setUser(null)
  }, [])

  useEffect(() => {
    // The API client calls this on a 401: the user disappears and RequireAuth shows the login page.
    setUnauthorizedHandler(() => setUser(null))

    if (tokenStorage.get() === null) {
      return
    }
    fetchMe()
      .then(setUser)
      .catch(() => tokenStorage.clear())
      .finally(() => setLoading(false))
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const response = await loginRequest(email, password)
    tokenStorage.set(response.accessToken)
    setUser(response.user)
  }, [])

  const value = useMemo(() => ({ user, loading, login, logout }), [user, loading, login, logout])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

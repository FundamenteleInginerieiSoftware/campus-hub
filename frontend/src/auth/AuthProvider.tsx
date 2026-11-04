import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { fetchMe, login as loginRequest, type User } from '../api/auth.ts'
import { ApiError, setUnauthorizedHandler, tokenStorage } from '../api/client.ts'
import { AuthContext } from './useAuth.ts'

// Holds the logged-in user for the whole app. On startup, a token saved by an earlier visit is
// checked with GET /api/v1/users/me, so a reload keeps the user logged in.
export default function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(() => tokenStorage.get() !== null)
  const [startupError, setStartupError] = useState<unknown>(null)

  const logout = useCallback(() => {
    tokenStorage.clear()
    setUser(null)
    setStartupError(null)
  }, [])

  const checkSavedToken = useCallback(() => {
    fetchMe()
      .then((me) => {
        setUser(me)
        setStartupError(null)
      })
      .catch((err) => {
        // 401: the token is really invalid; apiFetch has already forgotten it, so the login page shows.
        // Anything else (502 while the backend restarts, no network): keep the token and offer a retry.
        if (!(err instanceof ApiError && err.status === 401)) {
          setStartupError(err)
        }
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    // The API client calls this on a 401: the user disappears and RequireAuth shows the login page.
    setUnauthorizedHandler(() => setUser(null))

    if (tokenStorage.get() !== null) {
      checkSavedToken()
    }
  }, [checkSavedToken])

  // The "Try again" button on the error screen.
  const retry = useCallback(() => {
    setLoading(true)
    setStartupError(null)
    checkSavedToken()
  }, [checkSavedToken])

  const login = useCallback(async (email: string, password: string) => {
    const response = await loginRequest(email, password)
    tokenStorage.set(response.accessToken)
    setUser(response.user)
    setStartupError(null)
  }, [])

  const value = useMemo(
    () => ({ user, loading, startupError, retry, login, logout }),
    [user, loading, startupError, retry, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

import { Center, Loader } from '@mantine/core'
import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuth } from './useAuth.ts'

// Wrap a page in <RequireAuth> to show it only to logged-in users; everyone else goes to /login.
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  const location = useLocation()

  if (loading) {
    return (
      <Center h="100vh">
        <Loader />
      </Center>
    )
  }
  if (!user) {
    // Remember where the user wanted to go, so the login page can send them back there.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  return children
}

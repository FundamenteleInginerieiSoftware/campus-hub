import { Navigate, Outlet } from 'react-router'
import { useAuth } from './useAuth.ts'

// Parent route for admin pages: students are sent back to the start page.
// This only hides the pages; the real protection is the backend (/api/v1/admin/** answers 403).
export default function RequireAdmin() {
  const { user } = useAuth()
  if (user?.role !== 'ADMIN') {
    return <Navigate to="/" replace />
  }
  return <Outlet />
}

import { Navigate, Route, Routes } from 'react-router'
import RequireAdmin from './auth/RequireAdmin.tsx'
import RequireAuth from './auth/RequireAuth.tsx'
import AppLayout from './layout/AppLayout.tsx'
import AdminInventoryPage from './pages/AdminInventoryPage.tsx'
import AssetDetailsPage from './pages/AssetDetailsPage.tsx'
import CatalogPage from './pages/CatalogPage.tsx'
import IncidentQueuePage from './pages/IncidentQueuePage.tsx'
import LoginPage from './pages/LoginPage.tsx'
import MyIncidentsPage from './pages/MyIncidentsPage.tsx'
import MyReservationsPage from './pages/MyReservationsPage.tsx'
import NotFoundPage from './pages/NotFoundPage.tsx'
import PlaceholderPage from './pages/PlaceholderPage.tsx'
import RegisterPage from './pages/RegisterPage.tsx'
import ReservationsDeskPage from './pages/ReservationsDeskPage.tsx'
import AdminAuditLogPage from './pages/AdminAuditLogPage.tsx'

// All pages of the app. Each owner replaces their <PlaceholderPage> with the real page component.
// Pages under <AppLayout> get the side menu and need a login; pages under "admin" also need ADMIN.
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/register" element={<RegisterPage />} />

      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/catalog" replace />} />

        {/* Student and admin */}
        <Route path="catalog" element={<CatalogPage />} />
        <Route path="catalog/:id" element={<AssetDetailsPage />} />
        <Route path="reservations" element={<MyReservationsPage />} />
        <Route path="incidents" element={<MyIncidentsPage />} />

        {/* Admin only */}
        <Route path="admin" element={<RequireAdmin />}>
          <Route index element={<Navigate to="reservations" replace />} />
          <Route path="reservations" element={<ReservationsDeskPage />} />
          <Route path="incidents" element={<IncidentQueuePage />} />
          <Route
            path="reassignments"
            element={
              <PlaceholderPage
                title="Reassignment log"
                description="Bookings moved automatically after an incident."
                owner="Stefania"
              />
            }
          />
          <Route path="inventory" element={<AdminInventoryPage />} />
          <Route path="audit-logs" element={<AdminAuditLogPage />} />
          <Route path="audit-log" element={<Navigate to="/admin/audit-logs" replace />} />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

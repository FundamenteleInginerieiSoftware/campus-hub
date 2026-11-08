import { Navigate, Route, Routes } from 'react-router'
import RequireAdmin from './auth/RequireAdmin.tsx'
import RequireAuth from './auth/RequireAuth.tsx'
import AppLayout from './layout/AppLayout.tsx'
import LoginPage from './pages/LoginPage.tsx'
import MyIncidentsPage from './pages/MyIncidentsPage.tsx'
import MyReservationsPage from './pages/MyReservationsPage.tsx'
import NotFoundPage from './pages/NotFoundPage.tsx'
import PlaceholderPage from './pages/PlaceholderPage.tsx'
import RegisterPage from './pages/RegisterPage.tsx'
import ReservationsDeskPage from './pages/ReservationsDeskPage.tsx'

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
        <Route
          path="catalog"
          element={<PlaceholderPage title="Equipment" description="Browse lab equipment and book a slot." owner="Liviu" />}
        />
        <Route path="reservations" element={<MyReservationsPage />} />
        <Route path="incidents" element={<MyIncidentsPage />} />

        {/* Admin only */}
        <Route path="admin" element={<RequireAdmin />}>
          <Route index element={<Navigate to="reservations" replace />} />
          <Route path="reservations" element={<ReservationsDeskPage />} />
          <Route
            path="incidents"
            element={
              <PlaceholderPage title="Incident queue" description="Reported problems to triage and resolve." owner="Stefania" />
            }
          />
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
          <Route
            path="inventory"
            element={
              <PlaceholderPage
                title="Inventory"
                description="Add, edit and retire equipment, categories and labs."
                owner="Liviu"
              />
            }
          />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

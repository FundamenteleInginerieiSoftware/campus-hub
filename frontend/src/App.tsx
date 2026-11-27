import { Navigate, Route, Routes } from 'react-router'
import RequireAdmin from './auth/RequireAdmin.tsx'
import RequireAuth from './auth/RequireAuth.tsx'
import AppLayout from './layout/AppLayout.tsx'
import AdminInventoryPage from './pages/AdminInventoryPage.tsx'
import AdminReliabilityPage from './pages/AdminReliabilityPage.tsx'
import AdminUsersPage from './pages/AdminUsersPage.tsx'
import AssetDetailsPage from './pages/AssetDetailsPage.tsx'
import CalendarSyncPage from './pages/CalendarSyncPage.tsx'
import CatalogPage from './pages/CatalogPage.tsx'
import IncidentDetailsPage from './pages/IncidentDetailsPage.tsx'
import IncidentQueuePage from './pages/IncidentQueuePage.tsx'
import LoginPage from './pages/LoginPage.tsx'
import MaintenanceSimulatorPage from './pages/MaintenanceSimulatorPage.tsx'
import MyIncidentsPage from './pages/MyIncidentsPage.tsx'
import MyReservationsPage from './pages/MyReservationsPage.tsx'
import NotFoundPage from './pages/NotFoundPage.tsx'
import PickupPassPage from './pages/PickupPassPage.tsx'
import ProjectDetailsPage from './pages/ProjectDetailsPage.tsx'
import ProjectsPage from './pages/ProjectsPage.tsx'
import ReassignmentLogPage from './pages/ReassignmentLogPage.tsx'
import RegisterPage from './pages/RegisterPage.tsx'
import ReservationsDeskPage from './pages/ReservationsDeskPage.tsx'
import AdminAuditLogPage from './pages/AdminAuditLogPage.tsx'

// All pages of the app.
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
        <Route path="calendar-sync" element={<CalendarSyncPage />} />
        <Route path="incidents" element={<MyIncidentsPage />} />
        {/* The reporter or an admin; the backend answers 404 for anyone else */}
        <Route path="incidents/:id" element={<IncidentDetailsPage />} />
        <Route path="pickup-pass" element={<PickupPassPage />} />
        <Route path="projects" element={<ProjectsPage />} />
        <Route path="projects/:slug" element={<ProjectDetailsPage />} />

        {/* Admin only */}
        <Route path="admin" element={<RequireAdmin />}>
          <Route index element={<Navigate to="reservations" replace />} />
          <Route path="reservations" element={<ReservationsDeskPage />} />
          <Route path="incidents" element={<IncidentQueuePage />} />
          <Route path="reassignments" element={<ReassignmentLogPage />} />
          <Route path="maintenance-simulator" element={<MaintenanceSimulatorPage />} />
          <Route path="users" element={<AdminUsersPage />} />
          <Route path="inventory" element={<AdminInventoryPage />} />
          <Route path="reliability" element={<AdminReliabilityPage />} />
          <Route path="audit-logs" element={<AdminAuditLogPage />} />
          <Route path="audit-log" element={<Navigate to="/admin/audit-logs" replace />} />
        </Route>

        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

import { Suspense, lazy, useEffect } from 'react'
import { Route, Routes, useLocation, useNavigate } from 'react-router-dom'

import ErrorBoundary from './components/ErrorBoundary.jsx'
import { LoadingBlock } from './components/ui/Feedback.jsx'
import { RequireAdmin, RequireAuth } from './routes/guards.jsx'
import { ADMIN_AUTH_EXPIRED_EVENT, AUTH_EXPIRED_EVENT } from './services/apiClient.js'
import { useAdminAuthStore } from './store/adminAuthStore.js'
import { useAuthStore } from './store/authStore.js'

const HomePage = lazy(() => import('./pages/HomePage.jsx'))
const LoginPage = lazy(() => import('./pages/LoginPage.jsx'))
const RegisterPage = lazy(() => import('./pages/RegisterPage.jsx'))
const CitySelectPage = lazy(() => import('./pages/CitySelectPage.jsx'))
const CityWorkspacePage = lazy(() => import('./pages/CityWorkspacePage.jsx'))
const PropertyDetailPage = lazy(() => import('./pages/PropertyDetailPage.jsx'))
const UserWorkspacePage = lazy(() => import('./pages/UserWorkspacePage.jsx'))
const NotFoundPage = lazy(() => import('./pages/NotFoundPage.jsx'))

const AdminLoginPage = lazy(() => import('./pages/admin/AdminLoginPage.jsx'))
const AdminLayout = lazy(() => import('./layouts/AdminLayout.jsx'))
const AdminOverviewPage = lazy(() => import('./pages/admin/AdminOverviewPage.jsx'))
const AdminUsersPage = lazy(() => import('./pages/admin/AdminUsersPage.jsx'))
const AdminIngestionPage = lazy(() => import('./pages/admin/AdminIngestionPage.jsx'))
const AdminListingsPage = lazy(() => import('./pages/admin/AdminListingsPage.jsx'))
const AdminVectorStorePage = lazy(() => import('./pages/admin/AdminVectorStorePage.jsx'))
const AdminGraphStorePage = lazy(() => import('./pages/admin/AdminGraphStorePage.jsx'))
const AdminAuditsPage = lazy(() => import('./pages/admin/AdminAuditsPage.jsx'))
const AdminTracesPage = lazy(() => import('./pages/admin/AdminTracesPage.jsx'))
const AdminLogsPage = lazy(() => import('./pages/admin/AdminLogsPage.jsx'))
const AdminNotificationsPage = lazy(() => import('./pages/admin/AdminNotificationsPage.jsx'))
const AdminIntegrationsPage = lazy(() => import('./pages/admin/AdminIntegrationsPage.jsx'))

/**
 * 应用根组件：注册全局路由，并监听 401 事件统一跳转登录页。
 */
function App() {
  const navigate = useNavigate()
  const location = useLocation()
  const resetUser = useAuthStore((state) => state.reset)
  const resetAdmin = useAdminAuthStore((state) => state.reset)

  useEffect(() => {
    const onUserExpired = () => {
      resetUser()
      if (!location.pathname.startsWith('/login')) {
        navigate('/login', { state: { from: location.pathname + location.search } })
      }
    }
    const onAdminExpired = () => {
      resetAdmin()
      if (!location.pathname.startsWith('/admin/login')) {
        navigate('/admin/login', { state: { from: location.pathname } })
      }
    }
    window.addEventListener(AUTH_EXPIRED_EVENT, onUserExpired)
    window.addEventListener(ADMIN_AUTH_EXPIRED_EVENT, onAdminExpired)
    return () => {
      window.removeEventListener(AUTH_EXPIRED_EVENT, onUserExpired)
      window.removeEventListener(ADMIN_AUTH_EXPIRED_EVENT, onAdminExpired)
    }
  }, [navigate, location, resetUser, resetAdmin])

  return (
    <ErrorBoundary>
      <Suspense fallback={<LoadingBlock text="页面加载中…" className="min-h-screen" />}>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/cities" element={<CitySelectPage />} />
          <Route
            path="/city/:cityName"
            element={(
              <RequireAuth>
                <CityWorkspacePage />
              </RequireAuth>
            )}
          />
          <Route
            path="/property/:listingId"
            element={(
              <RequireAuth>
                <PropertyDetailPage />
              </RequireAuth>
            )}
          />
          <Route
            path="/workspace"
            element={(
              <RequireAuth>
                <UserWorkspacePage />
              </RequireAuth>
            )}
          />

          <Route path="/admin/login" element={<AdminLoginPage />} />
          <Route
            path="/admin"
            element={(
              <RequireAdmin>
                <AdminLayout />
              </RequireAdmin>
            )}
          >
            <Route index element={<AdminOverviewPage />} />
            <Route path="users" element={<AdminUsersPage />} />
            <Route path="ingestion" element={<AdminIngestionPage />} />
            <Route path="listings" element={<AdminListingsPage />} />
            <Route path="vector-store" element={<AdminVectorStorePage />} />
            <Route path="graph-store" element={<AdminGraphStorePage />} />
            <Route path="audits" element={<AdminAuditsPage />} />
            <Route path="traces" element={<AdminTracesPage />} />
            <Route path="logs" element={<AdminLogsPage />} />
            <Route path="notifications" element={<AdminNotificationsPage />} />
            <Route path="integrations" element={<AdminIntegrationsPage />} />
          </Route>

          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </Suspense>
    </ErrorBoundary>
  )
}

export default App

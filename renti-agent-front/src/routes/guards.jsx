import { useEffect } from 'react'
import { Navigate, useLocation } from 'react-router-dom'

import { LoadingBlock } from '../components/ui/Feedback.jsx'
import { useAdminAuthStore } from '../store/adminAuthStore.js'
import { useAuthStore } from '../store/authStore.js'

/**
 * 用户端路由守卫：未登录跳转 /login，并携带回跳地址。
 */
export function RequireAuth({ children }) {
  const { status, fetchSession } = useAuthStore()
  const location = useLocation()

  useEffect(() => {
    if (status === 'idle') fetchSession()
  }, [status, fetchSession])

  if (status === 'idle' || status === 'loading') {
    return <LoadingBlock text="正在确认登录状态…" className="min-h-[50vh]" />
  }
  if (status !== 'authenticated') {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }
  return children
}

/**
 * 管理端路由守卫：未登录跳转 /admin/login。
 */
export function RequireAdmin({ children }) {
  const { status, fetchSession } = useAdminAuthStore()
  const location = useLocation()

  useEffect(() => {
    if (status === 'idle') fetchSession()
  }, [status, fetchSession])

  if (status === 'idle' || status === 'loading') {
    return <LoadingBlock text="正在确认管理员身份…" className="min-h-[50vh]" />
  }
  if (status !== 'authenticated') {
    return <Navigate to="/admin/login" replace state={{ from: location.pathname }} />
  }
  return children
}

import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import Button from '../../components/ui/Button.jsx'
import { TextField } from '../../components/ui/Input.jsx'
import { useAdminAuthStore } from '../../store/adminAuthStore.js'

/**
 * 管理后台登录页：全屏深色渐变背景 + 居中白卡。
 */
function AdminLoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const login = useAdminAuthStore((state) => state.login)

  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const handleSubmit = async (event) => {
    event.preventDefault()
    if (!username.trim() || !password) {
      setError('请输入管理员账号与密码')
      return
    }
    setSubmitting(true)
    setError('')
    try {
      const data = await login({ username: username.trim(), password })
      if (data?.ok && data?.admin) {
        navigate(location.state?.from || '/admin', { replace: true })
      } else {
        setError(data?.summary || data?.message || '登录失败，请检查账号密码')
      }
    } catch (err) {
      setError(err?.message || '登录失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden bg-gradient-to-br from-ink-950 via-ink-900 to-brand-950 px-4">
      {/* 辅助光斑 */}
      <div className="pointer-events-none absolute -left-32 -top-32 h-96 w-96 rounded-full bg-brand-500/20 blur-3xl" aria-hidden="true" />
      <div className="pointer-events-none absolute -bottom-40 -right-24 h-[28rem] w-[28rem] rounded-full bg-sky-400/10 blur-3xl" aria-hidden="true" />

      <div className="relative w-full max-w-sm animate-fade-up rounded-2xl bg-white p-8 shadow-float">
        <div className="mb-6 flex flex-col items-center gap-3">
          <span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-600 text-lg font-bold text-white">
            R
          </span>
          <div className="text-center">
            <h1 className="text-lg font-semibold text-ink-900">管理控制台</h1>
            <p className="mt-0.5 text-xs text-ink-400">Renti Agent · 仅限授权管理员访问</p>
          </div>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <TextField
            label="管理员账号"
            autoComplete="username"
            placeholder="用户名或邮箱"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
          <TextField
            label="密码"
            type="password"
            autoComplete="current-password"
            placeholder="请输入密码"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          {error && (
            <p className="rounded-xl bg-rose-50 px-3 py-2 text-xs text-rose-700 ring-1 ring-inset ring-rose-200" role="alert">
              {error}
            </p>
          )}
          <Button type="submit" block loading={submitting}>
            登录
          </Button>
        </form>
      </div>
    </div>
  )
}

export default AdminLoginPage

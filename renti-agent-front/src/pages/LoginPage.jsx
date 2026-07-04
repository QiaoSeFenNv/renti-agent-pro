import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import AuthShell from '../components/site/AuthShell.jsx'
import Button from '../components/ui/Button.jsx'
import Card, { CardBody } from '../components/ui/Card.jsx'
import TextField from '../components/ui/Input.jsx'
import { useAuthStore } from '../store/authStore.js'

/**
 * 独立登录页：email + password。
 * 成功后跳回 location.state.from 或 /cities；
 * 邮箱未验证时引导到注册页的验证码步骤。
 */
function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const login = useAuthStore((state) => state.login)

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState({})
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const goVerify = (verifyEmail, devCode) => {
    navigate('/register', {
      state: { step: 'verify', email: verifyEmail || email, devCode: devCode || '' },
    })
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    const errors = {}
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) errors.email = '请输入有效的邮箱地址'
    if (!password) errors.password = '请输入密码'
    setFieldErrors(errors)
    setError('')
    if (Object.keys(errors).length > 0) return

    setLoading(true)
    try {
      const data = await login({ email: email.trim(), password })
      if (data?.requiresVerification) {
        setError('该邮箱尚未完成验证，请先输入验证码完成验证。')
        goVerify(data.email, data.devVerificationCode)
        return
      }
      if (data?.ok && data?.user) {
        navigate(location.state?.from || '/cities', { replace: true })
        return
      }
      setFieldErrors(data?.fieldErrors || {})
      setError(data?.summary || data?.message || '登录失败，请检查邮箱与密码。')
    } catch (err) {
      if (err?.code === 'email_unverified' || err?.payload?.requiresVerification) {
        setError('该邮箱尚未完成验证，请先输入验证码完成验证。')
        goVerify(err?.payload?.email, err?.payload?.devVerificationCode)
        return
      }
      setFieldErrors(err?.payload?.fieldErrors || {})
      setError(err.message || '登录失败，请稍后重试。')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell
      brandTitle="欢迎回来，继续你的找房进程"
      brandDescription="地图圈定 + AI 需求理解，收藏、历史与个性化设置都会在登录后同步到你的工作台。"
    >
      <Card>
        <CardBody className="px-6 py-8 sm:px-8">
          <h1 className="text-2xl font-semibold tracking-tight text-ink-900">登录</h1>
          <p className="mt-1.5 text-sm text-ink-500">使用注册邮箱登录 Renti Agent</p>

          <form className="mt-6 space-y-4" onSubmit={handleSubmit} noValidate>
            <TextField
              label="邮箱"
              type="email"
              name="email"
              autoComplete="email"
              placeholder="you@example.com"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              error={fieldErrors.email}
            />
            <TextField
              label="密码"
              type="password"
              name="password"
              autoComplete="current-password"
              placeholder="请输入密码"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              error={fieldErrors.password}
            />

            {error && (
              <p className="rounded-xl bg-rose-50 px-3.5 py-2.5 text-sm text-rose-700 ring-1 ring-inset ring-rose-100" role="alert">
                {error}
              </p>
            )}

            <Button type="submit" block size="lg" loading={loading}>
              登录
            </Button>
          </form>

          <p className="mt-6 text-center text-sm text-ink-500">
            没有账号？
            <Link to="/register" className="ml-1 font-medium text-brand-300 transition hover:text-brand-200">
              去注册
            </Link>
          </p>
        </CardBody>
      </Card>
    </AuthShell>
  )
}

export default LoginPage

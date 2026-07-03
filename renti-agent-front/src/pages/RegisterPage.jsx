import { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'

import AuthShell from '../components/site/AuthShell.jsx'
import Button from '../components/ui/Button.jsx'
import Card, { CardBody } from '../components/ui/Card.jsx'
import TextField from '../components/ui/Input.jsx'
import { useAuthStore } from '../store/authStore.js'

/**
 * 独立注册页：两步流程。
 * ① 填写 email / 昵称 / 密码 → authStore.register
 * ② 输入 6 位邮箱验证码 → authStore.verifyEmail → 跳 /login
 * 支持从登录页跳入直接进入验证步骤（location.state.step === 'verify'）。
 */
function RegisterPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const register = useAuthStore((state) => state.register)
  const verifyEmail = useAuthStore((state) => state.verifyEmail)

  const initialState = location.state || {}
  const [step, setStep] = useState(initialState.step === 'verify' ? 'verify' : 'form')

  const [email, setEmail] = useState(initialState.email || '')
  const [nickname, setNickname] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [code, setCode] = useState('')
  const [devCode, setDevCode] = useState(initialState.devCode || '')

  const [fieldErrors, setFieldErrors] = useState({})
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [loading, setLoading] = useState(false)

  const validateForm = () => {
    const errors = {}
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) errors.email = '请输入有效的邮箱地址'
    if (!nickname.trim()) errors.nickname = '请输入昵称'
    if (password.length < 10) {
      errors.password = '密码至少 10 位'
    } else if (!/[A-Za-z]/.test(password) || !/\d/.test(password)) {
      errors.password = '密码需同时包含字母和数字'
    } else if (/\s/.test(password)) {
      errors.password = '密码不能包含空格'
    }
    if (passwordConfirm !== password) errors.passwordConfirm = '两次输入的密码不一致'
    return errors
  }

  const handleRegister = async (event) => {
    event.preventDefault()
    const errors = validateForm()
    setFieldErrors(errors)
    setError('')
    if (Object.keys(errors).length > 0) return

    setLoading(true)
    try {
      const data = await register({ email: email.trim(), password, nickname: nickname.trim() })
      if (data && data.ok === false) {
        setFieldErrors(data.fieldErrors || {})
        setError(data.summary || data.message || '注册失败，请稍后重试。')
        return
      }
      setDevCode(data?.devVerificationCode || data?.devCode || '')
      setCode('')
      setFieldErrors({})
      setStep('verify')
    } catch (err) {
      setFieldErrors(err?.payload?.fieldErrors || {})
      setError(err.message || '注册失败，请稍后重试。')
    } finally {
      setLoading(false)
    }
  }

  const handleVerify = async (event) => {
    event.preventDefault()
    const errors = {}
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim())) errors.email = '请输入注册时使用的邮箱'
    if (!/^\d{6}$/.test(code.trim())) errors.code = '请输入 6 位数字验证码'
    setFieldErrors(errors)
    setError('')
    if (Object.keys(errors).length > 0) return

    setLoading(true)
    try {
      const data = await verifyEmail({ email: email.trim(), code: code.trim() })
      if (data && data.ok === false) {
        setFieldErrors(data.fieldErrors || {})
        setError(data.summary || data.message || '验证失败，请检查验证码。')
        return
      }
      setSuccess('邮箱验证成功，即将前往登录页…')
      setTimeout(() => navigate('/login', { replace: true }), 1200)
    } catch (err) {
      setFieldErrors(err?.payload?.fieldErrors || {})
      setError(err.message || '验证失败，请稍后重试。')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthShell
      brandTitle="创建账号，开启 AI 找房工作台"
      brandDescription="注册后即可使用地图圈定搜索、AI 推荐与深度分析，并同步收藏与偏好设置。"
    >
      <Card>
        <CardBody className="px-6 py-8 sm:px-8">
          {step === 'form' ? (
            <>
              <h1 className="text-2xl font-semibold tracking-tight text-ink-900">注册</h1>
              <p className="mt-1.5 text-sm text-ink-500">注册后会向邮箱发送验证码，完成验证即可登录</p>

              <form className="mt-6 space-y-4" onSubmit={handleRegister} noValidate>
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
                  label="昵称"
                  name="nickname"
                  autoComplete="nickname"
                  placeholder="工作台中展示的名字"
                  value={nickname}
                  onChange={(event) => setNickname(event.target.value)}
                  error={fieldErrors.nickname}
                />
                <TextField
                  label="密码"
                  type="password"
                  name="password"
                  autoComplete="new-password"
                  placeholder="至少 10 位，包含字母和数字"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  error={fieldErrors.password}
                />
                <TextField
                  label="确认密码"
                  type="password"
                  name="passwordConfirm"
                  autoComplete="new-password"
                  placeholder="再次输入密码"
                  value={passwordConfirm}
                  onChange={(event) => setPasswordConfirm(event.target.value)}
                  error={fieldErrors.passwordConfirm}
                />

                {error && (
                  <p className="rounded-xl bg-rose-50 px-3.5 py-2.5 text-sm text-rose-700 ring-1 ring-inset ring-rose-100" role="alert">
                    {error}
                  </p>
                )}

                <Button type="submit" block size="lg" loading={loading}>
                  注册并获取验证码
                </Button>
              </form>

              <p className="mt-6 text-center text-sm text-ink-500">
                已有账号？
                <Link to="/login" className="ml-1 font-medium text-brand-600 transition hover:text-brand-700">
                  去登录
                </Link>
              </p>
            </>
          ) : (
            <>
              <h1 className="text-2xl font-semibold tracking-tight text-ink-900">验证邮箱</h1>
              <p className="mt-1.5 text-sm text-ink-500">
                验证码已发送至 <span className="font-medium text-ink-700">{email || '你的邮箱'}</span>
                ，请输入 6 位验证码完成验证。
              </p>

              {devCode && (
                <p className="mt-4 rounded-xl bg-amber-50 px-3.5 py-2.5 text-sm text-amber-800 ring-1 ring-inset ring-amber-100">
                  开发模式验证码：<span className="font-mono font-semibold">{devCode}</span>
                </p>
              )}

              <form className="mt-6 space-y-4" onSubmit={handleVerify} noValidate>
                <TextField
                  label="邮箱"
                  type="email"
                  name="email"
                  autoComplete="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  error={fieldErrors.email}
                />
                <TextField
                  label="验证码"
                  name="code"
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="6 位数字"
                  value={code}
                  onChange={(event) => setCode(event.target.value.replace(/\D/g, ''))}
                  error={fieldErrors.code}
                  inputClassName="tracking-[0.4em] text-center font-mono text-lg"
                />

                {error && (
                  <p className="rounded-xl bg-rose-50 px-3.5 py-2.5 text-sm text-rose-700 ring-1 ring-inset ring-rose-100" role="alert">
                    {error}
                  </p>
                )}
                {success && (
                  <p className="rounded-xl bg-emerald-50 px-3.5 py-2.5 text-sm text-emerald-700 ring-1 ring-inset ring-emerald-100" role="status">
                    {success}
                  </p>
                )}

                <Button type="submit" block size="lg" loading={loading} disabled={Boolean(success)}>
                  完成验证
                </Button>
              </form>

              <div className="mt-6 flex items-center justify-between text-sm">
                <button
                  type="button"
                  onClick={() => {
                    setStep('form')
                    setError('')
                    setFieldErrors({})
                  }}
                  className="font-medium text-ink-500 transition hover:text-ink-700"
                >
                  返回修改注册信息
                </button>
                <Link to="/login" className="font-medium text-brand-600 transition hover:text-brand-700">
                  去登录
                </Link>
              </div>
            </>
          )}
        </CardBody>
      </Card>
    </AuthShell>
  )
}

export default RegisterPage

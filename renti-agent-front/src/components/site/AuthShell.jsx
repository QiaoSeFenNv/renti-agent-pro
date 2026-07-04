import { Link } from 'react-router-dom'

import { BrandMark } from './SiteHeader.jsx'

/**
 * 登录/注册页共用外壳：左侧夜色城市影像品牌区 + 右侧表单区。
 */
function AuthShell({ brandTitle, brandDescription, children }) {
  return (
    <div className="flex min-h-screen bg-ink-50">
      {/* 左侧品牌区：夜色街景 + 深色渐晕 */}
      <aside className="relative hidden w-[44%] flex-col justify-between overflow-hidden p-10 lg:flex">
        <img
          src="/img/auth-city.jpg"
          alt=""
          aria-hidden="true"
          className="absolute inset-0 h-full w-full object-cover"
        />
        <div className="absolute inset-0 bg-gradient-to-t from-surface-deep via-surface-deep/55 to-surface-deep/20" />
        <div className="absolute inset-0 bg-gradient-to-r from-transparent to-ink-50/95" />
        <div
          className="pointer-events-none absolute -left-24 top-1/4 h-96 w-96 rounded-full bg-brand-500/25 blur-3xl animate-float-slow"
          aria-hidden="true"
        />

        <Link to="/" className="relative flex items-center gap-2.5" aria-label="返回 Renti Agent 首页">
          <BrandMark />
          <span className="font-display text-base font-semibold text-white">Renti Agent</span>
        </Link>

        <div className="relative">
          <p className="font-mono text-xs uppercase tracking-[0.3em] text-brand-300">AI Rental Copilot</p>
          <h2 className="mt-3 max-w-md font-display text-3xl font-semibold leading-snug text-white">
            {brandTitle}
          </h2>
          <p className="mt-4 max-w-sm text-sm leading-6 text-ink-600">{brandDescription}</p>
        </div>

        <Link
          to="/"
          className="relative inline-flex items-center gap-1.5 text-sm text-ink-500 transition hover:text-white"
        >
          <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
            <path
              fillRule="evenodd"
              d="M12.78 4.22a.75.75 0 0 1 0 1.06L8.06 10l4.72 4.72a.75.75 0 1 1-1.06 1.06L6.44 10.53a.75.75 0 0 1 0-1.06l5.28-5.25a.75.75 0 0 1 1.06 0Z"
              clipRule="evenodd"
            />
          </svg>
          返回首页
        </Link>
      </aside>

      {/* 右侧表单区 */}
      <div className="relative flex flex-1 items-center justify-center overflow-hidden px-4 py-12 sm:px-6">
        <div className="bg-grid bg-grid-fade pointer-events-none absolute inset-0" aria-hidden="true" />
        <div
          className="pointer-events-none absolute -right-20 top-0 h-72 w-72 rounded-full bg-brand-500/10 blur-3xl"
          aria-hidden="true"
        />
        <div className="relative w-full max-w-md animate-fade-up">
          <Link to="/" className="mb-6 flex items-center gap-2 lg:hidden" aria-label="返回 Renti Agent 首页">
            <BrandMark className="h-7 w-7" />
            <span className="font-display text-sm font-semibold text-ink-900">Renti Agent</span>
          </Link>
          {children}
        </div>
      </div>
    </div>
  )
}

export default AuthShell

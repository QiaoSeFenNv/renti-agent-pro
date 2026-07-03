import { Link } from 'react-router-dom'

import { BrandMark } from './SiteHeader.jsx'

/**
 * 登录/注册页共用外壳：左侧品牌渐变区 + 右侧表单卡。
 */
function AuthShell({ brandTitle, brandDescription, children }) {
  return (
    <div className="flex min-h-screen bg-ink-50">
      {/* 左侧品牌区 */}
      <aside className="relative hidden w-[44%] flex-col justify-between overflow-hidden bg-ink-950 p-10 lg:flex">
        <div
          className="pointer-events-none absolute -left-24 top-1/4 h-96 w-96 rounded-full bg-brand-500/30 blur-3xl"
          aria-hidden="true"
        />
        <div
          className="pointer-events-none absolute -right-16 bottom-10 h-72 w-72 rounded-full bg-sky-400/20 blur-3xl"
          aria-hidden="true"
        />
        <Link to="/" className="relative flex items-center gap-2.5" aria-label="返回 Renti Agent 首页">
          <BrandMark />
          <span className="text-base font-semibold text-white">Renti Agent</span>
        </Link>
        <div className="relative">
          <h2 className="max-w-md text-3xl font-semibold leading-snug text-white">{brandTitle}</h2>
          <p className="mt-4 max-w-sm text-sm leading-6 text-ink-300">{brandDescription}</p>
        </div>
        <Link
          to="/"
          className="relative inline-flex items-center gap-1.5 text-sm text-ink-300 transition hover:text-white"
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
      <div className="relative flex flex-1 items-center justify-center px-4 py-12 sm:px-6">
        <div
          className="pointer-events-none absolute right-0 top-0 h-56 w-56 rounded-full bg-brand-400/10 blur-3xl lg:hidden"
          aria-hidden="true"
        />
        <div className="w-full max-w-md animate-fade-up">
          <Link to="/" className="mb-6 flex items-center gap-2 lg:hidden" aria-label="返回 Renti Agent 首页">
            <BrandMark className="h-7 w-7" />
            <span className="text-sm font-semibold text-ink-900">Renti Agent</span>
          </Link>
          {children}
        </div>
      </div>
    </div>
  )
}

export default AuthShell

import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useNavigate } from 'react-router-dom'

import { useAuthStore } from '../../store/authStore.js'
import Button from '../ui/Button.jsx'

/** 站点 logo：房子 + 火花 */
export function BrandMark({ className = 'h-8 w-8' }) {
  return (
    <span
      className={[
        'inline-flex items-center justify-center rounded-xl bg-brand-600 text-white shadow-sm',
        className,
      ].join(' ')}
      aria-hidden="true"
    >
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-5 w-5">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M3.5 10.5 12 3.75l8.5 6.75M5.5 9v10.5a.75.75 0 0 0 .75.75H10v-5.25h4v5.25h3.75a.75.75 0 0 0 .75-.75V9"
        />
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M18.75 2.25 19.3 3.7l1.45.55-1.45.55-.55 1.45-.55-1.45-1.45-.55 1.45-.55.55-1.45Z"
          fill="currentColor"
          strokeWidth="0.75"
        />
      </svg>
    </span>
  )
}

const NAV_ITEMS = [
  { to: '/', label: '首页', end: true },
  { to: '/cities', label: '城市工作台' },
  { to: '/workspace', label: '我的工作台' },
]

/**
 * 站点顶部导航：logo + 主导航 + 登录态区域。
 * 滚动后切换为白底阴影。
 */
function SiteHeader() {
  const navigate = useNavigate()
  const { status, user, fetchSession, logout } = useAuthStore()
  const [scrolled, setScrolled] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef(null)

  useEffect(() => {
    if (status === 'idle') fetchSession()
  }, [status, fetchSession])

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 8)
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    if (!menuOpen) return undefined
    const onClickOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', onClickOutside)
    return () => document.removeEventListener('mousedown', onClickOutside)
  }, [menuOpen])

  const nickname = user?.nickname || user?.displayName || user?.email || '用户'
  const avatarChar = nickname.trim().charAt(0).toUpperCase() || 'U'

  const handleLogout = async () => {
    setMenuOpen(false)
    await logout()
    navigate('/')
  }

  return (
    <header
      className={[
        'sticky top-0 z-40 transition-all duration-200',
        scrolled ? 'bg-white/95 shadow-card backdrop-blur' : 'bg-transparent',
      ].join(' ')}
    >
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between gap-4 px-4 sm:px-6">
        <Link to="/" className="flex items-center gap-2.5" aria-label="Renti Agent 首页">
          <BrandMark />
          <span className="text-base font-semibold tracking-tight text-ink-900">Renti Agent</span>
        </Link>

        <nav className="hidden items-center gap-1 md:flex" aria-label="主导航">
          {NAV_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                [
                  'rounded-full px-4 py-2 text-sm font-medium transition-colors',
                  isActive ? 'bg-brand-50 text-brand-700' : 'text-ink-600 hover:bg-ink-100 hover:text-ink-900',
                ].join(' ')
              }
            >
              {item.label}
            </NavLink>
          ))}
        </nav>

        <div className="flex items-center gap-2">
          {status === 'authenticated' && user ? (
            <div className="relative" ref={menuRef}>
              <button
                type="button"
                onClick={() => setMenuOpen((open) => !open)}
                aria-label="用户菜单"
                aria-expanded={menuOpen}
                className="flex items-center gap-2 rounded-full py-1 pl-1 pr-3 transition hover:bg-ink-100"
              >
                <span
                  className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-600 text-sm font-semibold text-white"
                  aria-hidden="true"
                >
                  {avatarChar}
                </span>
                <span className="max-w-[8rem] truncate text-sm font-medium text-ink-700">{nickname}</span>
                <svg
                  viewBox="0 0 20 20"
                  fill="currentColor"
                  className={['h-4 w-4 text-ink-400 transition-transform', menuOpen ? 'rotate-180' : ''].join(' ')}
                  aria-hidden="true"
                >
                  <path
                    fillRule="evenodd"
                    d="M5.22 7.22a.75.75 0 0 1 1.06 0L10 10.94l3.72-3.72a.75.75 0 1 1 1.06 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0L5.22 8.28a.75.75 0 0 1 0-1.06Z"
                    clipRule="evenodd"
                  />
                </svg>
              </button>
              {menuOpen && (
                <div className="absolute right-0 mt-2 w-44 overflow-hidden rounded-2xl bg-white py-1.5 shadow-float ring-1 ring-ink-100 animate-fade-in">
                  <Link
                    to="/workspace"
                    onClick={() => setMenuOpen(false)}
                    className="block px-4 py-2 text-sm text-ink-700 transition hover:bg-ink-50"
                  >
                    我的工作台
                  </Link>
                  <button
                    type="button"
                    onClick={handleLogout}
                    className="block w-full px-4 py-2 text-left text-sm text-rose-600 transition hover:bg-rose-50"
                  >
                    退出登录
                  </button>
                </div>
              )}
            </div>
          ) : (
            <>
              <Button variant="ghost" size="sm" onClick={() => navigate('/login')}>
                登录
              </Button>
              <Button size="sm" onClick={() => navigate('/register')}>
                注册
              </Button>
            </>
          )}
        </div>
      </div>

      {/* 移动端导航 */}
      <nav
        className="flex items-center gap-1 overflow-x-auto px-4 pb-2 md:hidden"
        aria-label="移动端导航"
      >
        {NAV_ITEMS.map((item) => (
          <NavLink
            key={item.to}
            to={item.to}
            end={item.end}
            className={({ isActive }) =>
              [
                'shrink-0 rounded-full px-3 py-1.5 text-xs font-medium transition-colors',
                isActive ? 'bg-brand-50 text-brand-700' : 'text-ink-600 hover:bg-ink-100',
              ].join(' ')
            }
          >
            {item.label}
          </NavLink>
        ))}
      </nav>
    </header>
  )
}

export default SiteHeader

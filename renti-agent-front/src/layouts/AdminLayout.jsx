import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'

import { useAdminAuthStore } from '../store/adminAuthStore.js'

function NavIcon({ d }) {
  return (
    <svg
      className="h-4 w-4 shrink-0"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={d} />
    </svg>
  )
}

/** 分组导航配置 */
const NAV_GROUPS = [
  {
    label: '概览',
    items: [
      { to: '/admin', end: true, label: '控制台总览', icon: 'M3 13h8V3H3v10zm10 8h8V11h-8v10zM3 21h8v-6H3v6zm10-18v6h8V3h-8z' },
    ],
  },
  {
    label: '数据接入',
    items: [
      { to: '/admin/ingestion', label: '采集中心', icon: 'M4 13h4l2 3h4l2-3h4M4 13l2-7h12l2 7M4 13v5a1 1 0 001 1h14a1 1 0 001-1v-5' },
      { to: '/admin/listings', label: '房源管理', icon: 'M3 11l9-7 9 7M5 10v9a1 1 0 001 1h4v-6h4v6h4a1 1 0 001-1v-9' },
    ],
  },
  {
    label: '检索引擎',
    items: [
      { to: '/admin/vector-store', label: '向量库 Qdrant', icon: 'M12 3l8 4.5v9L12 21l-8-4.5v-9L12 3zm0 9v9m0-9L4 7.5M12 12l8-4.5' },
      { to: '/admin/graph-store', label: '图谱 Neo4j', icon: 'M6 18a2 2 0 100-4 2 2 0 000 4zm12-8a2 2 0 100-4 2 2 0 000 4zm0 10a2 2 0 100-4 2 2 0 000 4zM7.5 14.5l9-6m-9 7l9 2' },
    ],
  },
  {
    label: '观测',
    items: [
      { to: '/admin/audits', label: '检索审计', icon: 'M21 21l-4.35-4.35M17 11a6 6 0 11-12 0 6 6 0 0112 0z' },
      { to: '/admin/traces', label: 'Agent 追踪', icon: 'M6 19a2 2 0 100-4 2 2 0 000 4zm12-10a2 2 0 100-4 2 2 0 000 4zM6 15V9a4 4 0 014-4h4m4 4v6a4 4 0 01-4 4h-1' },
      { to: '/admin/logs', label: '系统日志', icon: 'M8 3h6l5 5v12a1 1 0 01-1 1H8a2 2 0 01-2-2V5a2 2 0 012-2zm6 0v5h5M9 13h6M9 17h4' },
    ],
  },
  {
    label: '平台',
    items: [
      { to: '/admin/users', label: '用户管理', icon: 'M16 19v-1a4 4 0 00-4-4H7a4 4 0 00-4 4v1m18 0v-1a4 4 0 00-3-3.87M13 7a3 3 0 11-6 0 3 3 0 016 0zm3-2.87a3 3 0 010 5.74' },
      { to: '/admin/notifications', label: '公告通知', icon: 'M15 17H9m6 0h4l-1.4-2.33a2 2 0 01-.3-1.07V11a5.3 5.3 0 10-10.6 0v2.6a2 2 0 01-.3 1.07L5 17h4m6 0a3 3 0 11-6 0' },
      { to: '/admin/integrations', label: '集成配置', icon: 'M10.5 6h9.75M10.5 6a1.5 1.5 0 11-3 0m3 0a1.5 1.5 0 10-3 0M3.75 6H7.5m3 12h9.75m-9.75 0a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m-3.75 0H7.5m9-6h3.75m-3.75 0a1.5 1.5 0 01-3 0m3 0a1.5 1.5 0 00-3 0m-9.75 0h9.75' },
    ],
  },
]

/** 路由 -> 面包屑标题映射 */
const TITLES = NAV_GROUPS.flatMap((group) => group.items).reduce((acc, item) => {
  acc[item.to] = item.label
  return acc
}, {})

function SidebarContent({ onNavigate }) {
  const admin = useAdminAuthStore((state) => state.admin)
  const logout = useAdminAuthStore((state) => state.logout)
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/admin/login')
  }

  return (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-2.5 px-5 py-5">
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-brand-gradient text-sm font-bold text-white shadow-glow">
          R
        </span>
        <div className="min-w-0">
          <p className="font-display text-sm font-semibold text-white">Renti Agent</p>
          <p className="font-mono text-[11px] uppercase tracking-wider text-ink-400">Admin Console</p>
        </div>
      </div>

      <nav className="flex-1 overflow-y-auto px-3 pb-4 scrollbar-thin" aria-label="管理导航">
        {NAV_GROUPS.map((group) => (
          <div key={group.label} className="mt-4 first:mt-1">
            <p className="px-2 pb-1.5 text-[11px] font-semibold uppercase tracking-wider text-ink-500">
              {group.label}
            </p>
            <ul className="space-y-0.5">
              {group.items.map((item) => (
                <li key={item.to}>
                  <NavLink
                    to={item.to}
                    end={item.end}
                    onClick={onNavigate}
                    className={({ isActive }) =>
                      [
                        'relative flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm transition-colors',
                        isActive
                          ? 'bg-white/[0.07] font-medium text-white before:absolute before:inset-y-1.5 before:left-0 before:w-1 before:rounded-full before:bg-brand-gradient'
                          : 'text-ink-500 hover:bg-white/[0.05] hover:text-white',
                      ].join(' ')
                    }
                  >
                    <NavIcon d={item.icon} />
                    <span>{item.label}</span>
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </nav>

      <div className="border-t border-white/[0.08] px-4 py-4">
        <div className="flex items-center justify-between gap-2">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium text-white">
              {admin?.username ?? admin?.displayName ?? admin?.email ?? '管理员'}
            </p>
            <p className="text-[11px] text-ink-400">已登录</p>
          </div>
          <button
            type="button"
            onClick={handleLogout}
            className="shrink-0 rounded-full px-3 py-1.5 text-xs text-ink-500 ring-1 ring-inset ring-white/15 transition hover:bg-white/10 hover:text-white"
          >
            退出
          </button>
        </div>
      </div>
    </div>
  )
}

/**
 * 管理后台骨架：左侧 240px 深色固定侧边栏 + 右侧内容区（顶部面包屑条）。
 * 移动端侧边栏折叠为抽屉。
 */
function AdminLayout() {
  const location = useLocation()
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)

  const pageTitle = TITLES[location.pathname] ?? '控制台总览'

  useEffect(() => {
    setDrawerOpen(false)
  }, [location.pathname])

  return (
    <div className="flex min-h-screen bg-ink-50">
      {/* 桌面侧边栏 */}
      <aside className="fixed inset-y-0 left-0 z-30 hidden w-60 border-r border-white/[0.06] bg-surface-deep lg:block">
        <SidebarContent />
      </aside>

      {/* 移动端抽屉 */}
      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true" aria-label="管理导航">
          <div className="absolute inset-0 bg-black/70 backdrop-blur-sm animate-fade-in" onClick={() => setDrawerOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-60 bg-surface-deep shadow-float ring-1 ring-white/10">
            <SidebarContent onNavigate={() => setDrawerOpen(false)} />
          </aside>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col lg:pl-60">
        {/* 顶部面包屑条 */}
        <header className="sticky top-0 z-20 flex h-12 items-center justify-between gap-3 border-b border-white/[0.06] bg-surface-deep/70 px-4 backdrop-blur-xl sm:px-6">
          <div className="flex items-center gap-2 text-sm">
            <button
              type="button"
              onClick={() => setDrawerOpen(true)}
              aria-label="打开导航"
              className="rounded-lg p-1.5 text-ink-500 hover:bg-ink-100 lg:hidden"
            >
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" aria-hidden="true">
                <path d="M4 6h16M4 12h16M4 18h16" />
              </svg>
            </button>
            <span className="hidden text-ink-400 sm:inline">管理后台</span>
            <span className="hidden text-ink-300 sm:inline">/</span>
            <span className="font-medium text-ink-900">{pageTitle}</span>
          </div>
          <button
            type="button"
            onClick={() => setRefreshKey((key) => key + 1)}
            aria-label="刷新当前页数据"
            className="flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs text-ink-500 ring-1 ring-inset ring-white/10 transition hover:bg-white/[0.06] hover:text-white"
          >
            <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M4 4v6h6M20 20v-6h-6M20 9a8 8 0 00-14.5-3M4 15a8 8 0 0014.5 3" />
            </svg>
            刷新
          </button>
        </header>

        {/* key 变化触发子页面整体重挂载，实现「刷新」 */}
        <main className="min-w-0 flex-1 px-4 py-6 sm:px-6" key={refreshKey}>
          <Outlet />
        </main>
      </div>
    </div>
  )
}

export default AdminLayout

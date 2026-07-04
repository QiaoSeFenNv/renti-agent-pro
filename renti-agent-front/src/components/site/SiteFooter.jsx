import { Link } from 'react-router-dom'

import { BrandMark } from './SiteHeader.jsx'

/**
 * 站点页脚：极简单区块——品牌 + 快捷链接 + 免责声明一行带过。
 */
function SiteFooter() {
  return (
    <footer className="border-t border-white/[0.06]">
      <div className="mx-auto flex max-w-7xl flex-col gap-6 px-4 py-10 sm:px-6 md:flex-row md:items-start md:justify-between">
        <div className="max-w-sm">
          <div className="flex items-center gap-2.5">
            <BrandMark className="h-7 w-7" />
            <span className="font-display text-sm font-semibold text-ink-900">Renti Agent</span>
          </div>
          <p className="mt-3 text-xs leading-5 text-ink-400">
            用地图圈定与 AI 需求理解，把租房决策讲清楚的智能找房工作台。
          </p>
        </div>
        <nav className="flex items-center gap-6 text-sm" aria-label="页脚导航">
          <Link to="/cities" className="text-ink-500 transition hover:text-white">
            城市工作台
          </Link>
          <Link to="/workspace" className="text-ink-500 transition hover:text-white">
            我的工作台
          </Link>
          <Link to="/register" className="text-ink-500 transition hover:text-white">
            注册账号
          </Link>
        </nav>
      </div>
      <div className="border-t border-white/[0.04]">
        <p className="mx-auto max-w-7xl px-4 py-4 text-xs leading-5 text-ink-300 sm:px-6">
          © {new Date().getFullYear()} Renti Agent · 房源数据仅供研究学习，不构成任何租赁建议，实际信息以来源平台与线下核实为准。
        </p>
      </div>
    </footer>
  )
}

export default SiteFooter

import { Link } from 'react-router-dom'

import { BrandMark } from './SiteHeader.jsx'

/**
 * 站点页脚：产品说明 / 快捷链接 / 免责声明 三列 + 版权行。
 */
function SiteFooter() {
  return (
    <footer className="border-t border-ink-100 bg-white">
      <div className="mx-auto grid max-w-7xl gap-8 px-4 py-10 sm:grid-cols-3 sm:px-6">
        <div>
          <div className="flex items-center gap-2.5">
            <BrandMark className="h-7 w-7" />
            <span className="text-sm font-semibold text-ink-900">Renti Agent</span>
          </div>
          <p className="mt-3 max-w-xs text-xs leading-5 text-ink-500">
            用地图圈定与 AI 需求理解，把租房决策讲清楚的智能找房工作台。
          </p>
        </div>
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-400">快捷链接</h3>
          <ul className="mt-3 space-y-2 text-sm">
            <li>
              <Link to="/cities" className="text-ink-600 transition hover:text-brand-600">
                城市工作台
              </Link>
            </li>
            <li>
              <Link to="/workspace" className="text-ink-600 transition hover:text-brand-600">
                我的工作台
              </Link>
            </li>
            <li>
              <Link to="/register" className="text-ink-600 transition hover:text-brand-600">
                注册账号
              </Link>
            </li>
          </ul>
        </div>
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-400">免责声明</h3>
          <p className="mt-3 text-xs leading-5 text-ink-500">
            房源数据仅供研究学习，不构成任何租赁建议；实际租赁信息请以房源来源平台与线下核实为准。
          </p>
        </div>
      </div>
      <div className="border-t border-ink-100">
        <p className="mx-auto max-w-7xl px-4 py-4 text-xs text-ink-400 sm:px-6">
          © {new Date().getFullYear()} Renti Agent · 让每一次租房决策有据可依
        </p>
      </div>
    </footer>
  )
}

export default SiteFooter

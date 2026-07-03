import SiteFooter from '../components/site/SiteFooter.jsx'
import SiteHeader from '../components/site/SiteHeader.jsx'

/**
 * 用户端通用布局：顶部导航 + 内容区 + 页脚。
 */
function SiteLayout({ children }) {
  return (
    <div className="flex min-h-screen flex-col bg-ink-50">
      <SiteHeader />
      <main className="flex-1">{children}</main>
      <SiteFooter />
    </div>
  )
}

export default SiteLayout

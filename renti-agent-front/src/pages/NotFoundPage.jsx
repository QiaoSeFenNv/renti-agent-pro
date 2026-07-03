import { Link } from 'react-router-dom'

import { BrandMark } from '../components/site/SiteHeader.jsx'
import Button from '../components/ui/Button.jsx'

/** 404 页面：简洁提示 + 回首页入口 */
function NotFoundPage() {
  return (
    <div className="relative flex min-h-screen flex-col items-center justify-center overflow-hidden bg-ink-50 px-4">
      <div
        className="pointer-events-none absolute -top-24 left-1/2 h-72 w-72 -translate-x-1/2 rounded-full bg-brand-400/20 blur-3xl"
        aria-hidden="true"
      />
      <div className="relative flex flex-col items-center text-center animate-fade-up">
        <BrandMark className="h-12 w-12" />
        <p className="mt-6 text-6xl font-semibold tracking-tight text-ink-900">404</p>
        <p className="mt-3 text-sm text-ink-500">页面不存在或已被移动，回到首页继续探索。</p>
        <Link to="/" className="mt-6">
          <Button size="lg">回到首页</Button>
        </Link>
      </div>
    </div>
  )
}

export default NotFoundPage

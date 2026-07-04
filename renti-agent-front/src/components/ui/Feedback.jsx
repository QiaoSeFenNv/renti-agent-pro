/** 加载指示器 */
export function Spinner({ className = 'h-5 w-5', label = '加载中' }) {
  return (
    <svg
      className={['animate-spin text-brand-400', className].join(' ')}
      viewBox="0 0 24 24"
      fill="none"
      role="status"
      aria-label={label}
    >
      <circle className="opacity-20" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-90" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
    </svg>
  )
}

/** 整块区域的加载占位 */
export function LoadingBlock({ text = '正在加载…', className = 'py-16' }) {
  return (
    <div className={['flex flex-col items-center justify-center gap-3 text-ink-400', className].join(' ')}>
      <Spinner />
      <p className="text-sm">{text}</p>
    </div>
  )
}

/** 空状态占位 */
export function EmptyState({ icon = '🗂️', title = '暂无数据', description, action, className = 'py-16' }) {
  return (
    <div className={['flex flex-col items-center justify-center gap-3 text-center', className].join(' ')}>
      <div
        className="flex h-14 w-14 items-center justify-center rounded-2xl bg-white/[0.04] text-2xl ring-1 ring-white/10"
        aria-hidden="true"
      >
        {icon}
      </div>
      <p className="text-sm font-medium text-ink-700">{title}</p>
      {description && <p className="max-w-sm text-xs leading-5 text-ink-400">{description}</p>}
      {action && <div className="mt-1">{action}</div>}
    </div>
  )
}

export default Spinner

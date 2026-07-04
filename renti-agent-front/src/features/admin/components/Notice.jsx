/**
 * 内联提示条：错误红条（可带重试）与成功绿条（配合 useFlash 使用）。
 */

export function ErrorBar({ error, onRetry, className = '' }) {
  if (!error) return null
  const message = typeof error === 'string' ? error : error?.message || '请求失败，请稍后重试'
  return (
    <div
      className={[
        'flex items-center justify-between gap-3 rounded-xl bg-rose-50 px-4 py-2.5 text-sm text-rose-700 ring-1 ring-inset ring-rose-200',
        className,
      ].join(' ')}
      role="alert"
    >
      <span className="min-w-0 break-all">{message}</span>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="shrink-0 rounded-full bg-rose-500/10 px-3 py-1 text-xs font-medium text-rose-700 ring-1 ring-inset ring-rose-200 transition hover:bg-rose-500/20"
        >
          重试
        </button>
      )}
    </div>
  )
}

export function SuccessBar({ message, className = '' }) {
  if (!message) return null
  return (
    <div
      className={[
        'animate-fade-in rounded-xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-700 ring-1 ring-inset ring-emerald-200',
        className,
      ].join(' ')}
      role="status"
    >
      {message}
    </div>
  )
}

/**
 * 徽章/标签：状态与关键词的轻量展示。
 * tone 与业务语义解耦，由调用方决定映射。
 */
const TONES = {
  brand: 'bg-brand-50 text-brand-700 ring-brand-600/10',
  neutral: 'bg-ink-100 text-ink-600 ring-ink-500/10',
  success: 'bg-emerald-50 text-emerald-700 ring-emerald-600/10',
  warning: 'bg-amber-50 text-amber-700 ring-amber-600/10',
  danger: 'bg-rose-50 text-rose-700 ring-rose-600/10',
  info: 'bg-sky-50 text-sky-700 ring-sky-600/10',
}

function Badge({ tone = 'neutral', className = '', children }) {
  return (
    <span
      className={[
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ring-1 ring-inset',
        TONES[tone] ?? TONES.neutral,
        className,
      ].join(' ')}
    >
      {children}
    </span>
  )
}

export default Badge

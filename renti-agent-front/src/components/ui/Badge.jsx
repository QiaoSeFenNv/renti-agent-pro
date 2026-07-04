/**
 * 徽章/标签：暗底半透明色块 + 同色描边，状态与关键词的轻量展示。
 * tone 与业务语义解耦，由调用方决定映射。
 */
const TONES = {
  brand: 'bg-brand-500/15 text-brand-300 ring-brand-400/25',
  neutral: 'bg-white/[0.06] text-ink-500 ring-white/10',
  success: 'bg-emerald-400/10 text-emerald-700 ring-emerald-400/25',
  warning: 'bg-amber-400/10 text-amber-700 ring-amber-400/25',
  danger: 'bg-rose-500/10 text-rose-700 ring-rose-500/25',
  info: 'bg-sky-400/10 text-sky-700 ring-sky-400/25',
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

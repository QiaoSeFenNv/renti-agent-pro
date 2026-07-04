/**
 * 指标卡：概览页/统计条使用的数字卡片。
 */
function StatCard({ label, value, hint, icon, tone = 'default', className = '' }) {
  const tones = {
    default: 'text-ink-900',
    brand: 'text-brand-300',
    success: 'text-emerald-700',
    warning: 'text-amber-700',
    danger: 'text-rose-700',
  }
  return (
    <div
      className={[
        'rounded-2xl bg-surface px-5 py-4 shadow-card ring-1 ring-white/[0.06] transition duration-300 hover:ring-white/[0.12]',
        className,
      ].join(' ')}
    >
      <div className="flex items-center justify-between gap-2">
        <p className="font-mono text-xs font-medium uppercase tracking-wide text-ink-400">{label}</p>
        {icon && <span className="text-base text-ink-300" aria-hidden="true">{icon}</span>}
      </div>
      <p className={['mt-1.5 font-mono text-2xl font-semibold tabular-nums', tones[tone] ?? tones.default].join(' ')}>
        {value ?? '—'}
      </p>
      {hint && <p className="mt-1 text-xs text-ink-400">{hint}</p>}
    </div>
  )
}

export default StatCard

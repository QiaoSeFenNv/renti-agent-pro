import { RADIUS_OPTIONS, readField, toNumber } from './utils.js'

const SORT_OPTIONS = [
  { value: 'score_desc', label: '综合评分' },
  { value: 'price_asc', label: '租金最低' },
]

/** 需求摘要卡：解析结果 + 半径调节 + 排序切换 */
function RequirementSummary({ parsed, radius, onRadiusChange, sort, onSortChange, disabled = false }) {
  const locationText = readField(parsed || {}, 'locationText', 'location_text') || ''
  const parsedRadius = toNumber(readField(parsed || {}, 'radiusMeters', 'radius_meters'))

  return (
    <section className="rounded-2xl bg-white/[0.04] p-4 ring-1 ring-white/[0.08]" aria-label="需求摘要">
      <h3 className="text-sm font-semibold text-ink-900">需求摘要</h3>
      {(locationText || Number.isFinite(parsedRadius)) && (
        <dl className="mt-2 space-y-1 text-xs leading-5">
          {locationText && (
            <div className="flex gap-2">
              <dt className="shrink-0 text-ink-400">定位关键词</dt>
              <dd className="truncate font-medium text-ink-700">{locationText}</dd>
            </div>
          )}
          {Number.isFinite(parsedRadius) && (
            <div className="flex gap-2">
              <dt className="shrink-0 text-ink-400">解析半径</dt>
              <dd className="font-mono font-medium text-brand-300">{parsedRadius}m</dd>
            </div>
          )}
        </dl>
      )}

      <div className="mt-3">
        <p className="text-xs font-medium text-ink-500">搜索半径（变更后自动重搜）</p>
        <div className="mt-1.5 grid grid-cols-4 gap-1.5" role="group" aria-label="搜索半径">
          {RADIUS_OPTIONS.map((value) => {
            const active = radius === value
            return (
              <button
                key={value}
                type="button"
                disabled={disabled}
                onClick={() => onRadiusChange(value)}
                aria-pressed={active}
                className={[
                  'rounded-full px-2 py-1.5 font-mono text-xs font-medium transition duration-200 disabled:cursor-not-allowed disabled:opacity-40',
                  active
                    ? 'bg-brand-gradient text-white shadow-glow'
                    : 'bg-white/[0.05] text-ink-500 ring-1 ring-inset ring-white/10 hover:bg-white/[0.09] hover:text-ink-900',
                ].join(' ')}
              >
                {value >= 1000 ? `${value / 1000}km` : `${value}m`}
              </button>
            )
          })}
        </div>
      </div>

      <div className="mt-3">
        <p className="text-xs font-medium text-ink-500">排序方式</p>
        <div className="mt-1.5 inline-flex rounded-full bg-black/30 p-0.5 ring-1 ring-inset ring-white/[0.06]" role="group" aria-label="排序方式">
          {SORT_OPTIONS.map((option) => {
            const active = sort === option.value
            return (
              <button
                key={option.value}
                type="button"
                onClick={() => onSortChange(option.value)}
                aria-pressed={active}
                className={[
                  'rounded-full px-3 py-1 text-xs font-medium transition duration-200',
                  active ? 'bg-white/[0.12] text-white shadow-sm' : 'text-ink-500 hover:text-ink-800',
                ].join(' ')}
              >
                {option.label}
              </button>
            )
          })}
        </div>
      </div>
    </section>
  )
}

export default RequirementSummary

import Badge from '../../components/ui/Badge.jsx'
import { formatDistance, formatPrice, imgSrc, verificationBadge } from './utils.js'

/** 推荐房源卡片：图 + 标题 + 价格 + 元信息 + tags/score + 风险提示 + 收藏/详情 */
function RecommendationCard({ item, active = false, favorite = false, onSelect, onDetail, onToggleFavorite }) {
  const metaParts = [
    item.layout,
    Number.isFinite(item.areaSqm) ? `${Math.round(item.areaSqm)}㎡` : '',
    Number.isFinite(item.distanceM) ? `距目标 ${formatDistance(item.distanceM)}` : '',
  ].filter(Boolean)
  const locationText =
    [item.district, item.businessArea, item.community].filter(Boolean).join(' · ') || item.location || ''
  const verifyBadge = verificationBadge(item.verified)

  const handleKeyDown = (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onSelect?.(item)
    }
  }

  return (
    <article
      id={`listing-card-${item.id}`}
      role="button"
      tabIndex={0}
      aria-label={`定位房源：${item.title}`}
      onClick={() => onSelect?.(item)}
      onKeyDown={handleKeyDown}
      className={[
        'group cursor-pointer rounded-2xl p-3 transition duration-200',
        active
          ? 'bg-sky-700/[0.08] ring-2 ring-sky-700/60 shadow-glow'
          : 'bg-white/[0.04] ring-1 ring-white/[0.07] hover:bg-sky-50/[0.08] hover:ring-sky-700/35',
      ].join(' ')}
    >
      <div className="flex gap-3">
        <div className="relative h-20 w-24 shrink-0 overflow-hidden rounded-xl bg-gradient-to-br from-brand-50 to-sky-100 ring-1 ring-white/[0.06]">
          {item.image ? (
            <img
              src={imgSrc(item.image)}
              alt={item.title}
              loading="lazy"
              className="h-full w-full object-cover transition duration-300 group-hover:scale-105"
              onError={(event) => {
                event.currentTarget.style.display = 'none'
              }}
            />
          ) : (
            <span className="flex h-full w-full items-center justify-center text-ink-300" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-6 w-6">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="m2.25 15.75 5.159-5.159a2.25 2.25 0 0 1 3.182 0l5.159 5.159m-1.5-1.5 1.409-1.409a2.25 2.25 0 0 1 3.182 0l2.909 2.909M3.75 21h16.5A1.5 1.5 0 0 0 21.75 19.5V4.5A1.5 1.5 0 0 0 20.25 3H3.75A1.5 1.5 0 0 0 2.25 4.5v15A1.5 1.5 0 0 0 3.75 21Z"
                />
              </svg>
            </span>
          )}
        </div>

        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-2">
            <h3 className="truncate text-sm font-semibold text-ink-900">{item.title}</h3>
            <div className="flex shrink-0 items-center gap-1">
              {verifyBadge && (
                <Badge tone={verifyBadge.tone} className="shrink-0" >
                  <span title={verifyBadge.title}>{verifyBadge.icon} {verifyBadge.label}</span>
                </Badge>
              )}
              <button
              type="button"
              onClick={(event) => {
                event.stopPropagation()
                onToggleFavorite?.(item)
              }}
              aria-label={favorite ? `取消收藏 ${item.title}` : `收藏 ${item.title}`}
              aria-pressed={favorite}
              className={[
                'shrink-0 rounded-full p-1 transition',
                favorite
                  ? 'text-rose-500 hover:bg-rose-500/10'
                  : 'text-ink-300 hover:bg-white/[0.08] hover:text-rose-400',
              ].join(' ')}
            >
              <svg viewBox="0 0 24 24" fill={favorite ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.5" className="h-4 w-4" aria-hidden="true">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  d="M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12Z"
                />
              </svg>
            </button>
            </div>
          </div>
          {locationText && <p className="mt-0.5 truncate text-xs text-ink-400">{locationText}</p>}
          <div className="mt-1.5 flex items-baseline justify-between gap-2">
            <p className="font-mono text-sm font-semibold text-brand-300">
              {Number.isFinite(item.rentPrice) ? (
                <>
                  {formatPrice(item.rentPrice)}
                  <span className="font-sans text-xs font-normal text-ink-400">/月</span>
                </>
              ) : (
                item.priceLabel || '价格待确认'
              )}
            </p>
            {metaParts.length > 0 && <p className="truncate text-xs text-ink-500">{metaParts.join(' · ')}</p>}
          </div>
        </div>
      </div>

      {(item.score > 0 || item.tags.length > 0 || item.withinRadius === false) && (
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          {item.score > 0 && <Badge tone="brand">评分 {Math.round(item.score)}</Badge>}
          {item.tags.slice(0, 3).map((tag) => (
            <Badge key={tag}>{tag}</Badge>
          ))}
          {item.withinRadius === false && <Badge tone="warning">超出半径</Badge>}
        </div>
      )}

      {item.riskNotes.length > 0 && (
        <p className="mt-1.5 truncate text-xs text-rose-700" title={item.riskNotes.join('；')}>
          ⚠ {item.riskNotes[0]}
        </p>
      )}

      <div className="mt-2 flex items-center justify-between">
        {item.reasons.length > 0 ? (
          <p className="truncate text-xs text-ink-400" title={item.reasons.join('；')}>
            {item.reasons[0]}
          </p>
        ) : (
          <span />
        )}
        <button
          type="button"
          onClick={(event) => {
            event.stopPropagation()
            onDetail?.(item)
          }}
          className="shrink-0 rounded-full px-2.5 py-1 text-xs font-medium text-sky-700 transition hover:bg-sky-700/12 hover:text-sky-800"
        >
          详情 →
        </button>
      </div>
    </article>
  )
}

export default RecommendationCard

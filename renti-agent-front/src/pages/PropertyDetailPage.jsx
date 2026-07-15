import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'

import { BrandMark } from '../components/site/SiteHeader.jsx'
import Badge from '../components/ui/Badge.jsx'
import Button from '../components/ui/Button.jsx'
import { EmptyState, LoadingBlock } from '../components/ui/Feedback.jsx'
import ChatDrawer from '../features/property/ChatDrawer.jsx'
import CommuteMapCard from '../features/property/CommuteMapCard.jsx'
import DataSourceCard from '../features/property/DataSourceCard.jsx'
import EvaluationCards from '../features/property/EvaluationCards.jsx'
import Gallery from '../features/property/Gallery.jsx'
import ScoreRing from '../features/property/ScoreRing.jsx'
import { formatDate, mergeDetailPatch, normalizeDetail } from '../features/property/detailUtils.js'
import { readField, verificationBadge } from '../features/workspace/utils.js'
import { listingService } from '../services/searchService.js'
import { userService } from '../services/userService.js'

/** commute 条目 icon 字段（描述性文本）→ 简单 SVG 映射 */
function CommuteIcon({ name }) {
  const value = String(name || '').toLowerCase()
  const common = 'h-4 w-4 text-ink-400'
  if (/subway|metro|train|地铁/.test(value)) {
    return (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className={common} aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M8.25 18.75 6 21m12-2.25L15.75 21M6.75 4.5h10.5a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5H6.75a1.5 1.5 0 0 1-1.5-1.5V6a1.5 1.5 0 0 1 1.5-1.5Zm-1.5 6h13.5M9 13.875h.008v.008H9v-.008Zm6 0h.008v.008H15v-.008Z"
        />
      </svg>
    )
  }
  if (/car|drive|driving|驾车|打车/.test(value)) {
    return (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className={common} aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M8.25 18.75a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m3 0h6m-9 0H3.375a1.125 1.125 0 0 1-1.125-1.125V14.25m17.25 4.5a1.5 1.5 0 0 1-3 0m3 0a1.5 1.5 0 0 0-3 0m3 0h1.125c.621 0 1.129-.504 1.09-1.124a17.902 17.902 0 0 0-3.213-9.193 2.056 2.056 0 0 0-1.58-.86H14.25M16.5 18.75h-2.25m0-11.177v-.958c0-.568-.422-1.048-.987-1.106a48.554 48.554 0 0 0-10.026 0 1.106 1.106 0 0 0-.987 1.106v7.635m12-6.677v6.677m0 4.5v-4.5m0 0h-12"
        />
      </svg>
    )
  }
  if (/walk|步行/.test(value)) {
    return (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className={common} aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M13.5 5.25a1.5 1.5 0 1 1-3 0 1.5 1.5 0 0 1 3 0ZM9.75 9l-2.25 9m3.75-9.75 1.5 3 2.25.75M9 21l2.25-4.5L13.5 18l1.5 3.75"
        />
      </svg>
    )
  }
  return <span className="mt-1 h-1.5 w-1.5 shrink-0 rounded-full bg-brand-400" aria-hidden="true" />
}

/**
 * 房源详情页：图集 + 信息卡 + 评估卡 + AI 深度分析 + 通勤小地图 + 数据来源 + 房源问答。
 */
function PropertyDetailPage() {
  const { listingId } = useParams()
  const [detailState, setDetailState] = useState({ status: 'loading', error: '', listing: null, detail: null })
  const [analysis, setAnalysis] = useState({ status: 'idle', error: '', payload: null })
  const [settings, setSettings] = useState(null)
  const [favorite, setFavorite] = useState(false)
  const [favoriteBusy, setFavoriteBusy] = useState(false)
  const [favoriteError, setFavoriteError] = useState('')
  const [chatOpen, setChatOpen] = useState(false)
  const seqRef = useRef(0)

  const lastCity = typeof window !== 'undefined' ? localStorage.getItem('renti.lastCity') || '' : ''
  const workspaceMode = typeof window !== 'undefined' ? localStorage.getItem('renti.workspaceMode') || 'system_search' : 'system_search'
  const backTo = lastCity ? `/city/${encodeURIComponent(lastCity)}?mode=${workspaceMode}` : '/cities'

  /* ---------------- 详情加载 ---------------- */

  const loadDetail = useCallback(async () => {
    const seq = ++seqRef.current
    setDetailState({ status: 'loading', error: '', listing: null, detail: null })
    setAnalysis({ status: 'idle', error: '', payload: null })
    try {
      const data = await listingService.getDetail(listingId)
      if (seq !== seqRef.current) return
      if (data?.ok === false || (!data?.detail && !data?.listing)) {
        throw new Error(data?.summary || '房源详情不存在或已下架')
      }
      setDetailState({ status: 'ready', error: '', listing: data.listing || null, detail: data.detail || null })
    } catch (err) {
      if (seq !== seqRef.current) return
      setDetailState({ status: 'error', error: err?.message || '房源详情加载失败', listing: null, detail: null })
    }
  }, [listingId])

  useEffect(() => {
    loadDetail()
  }, [loadDetail])

  useEffect(() => {
    let disposed = false
    userService
      .getSettings()
      .then((data) => {
        if (!disposed) setSettings(data?.settings || data || null)
      })
      .catch(() => {})
    userService
      .getFavorites()
      .then((data) => {
        if (disposed) return
        const rows = Array.isArray(data?.favorites) ? data.favorites : []
        setFavorite(
          rows.some(
            (row) => String(readField(row, 'listingId', 'listing_id') ?? row?.listing?.id ?? '') === String(listingId),
          ),
        )
      })
      .catch(() => {})
    return () => {
      disposed = true
    }
  }, [listingId])

  const baseDetail = useMemo(
    () => normalizeDetail(detailState.detail, detailState.listing, listingId),
    [detailState.detail, detailState.listing, listingId],
  )
  const detail = useMemo(
    () => mergeDetailPatch(baseDetail, analysis.payload?.detailPatch),
    [baseDetail, analysis.payload],
  )
  const analysisMeta = analysis.payload?.detailPatch?.analysisMeta || analysis.payload?.analysis || null
  const analysisWarnings = Array.isArray(analysisMeta?.warnings) ? analysisMeta.warnings.filter(Boolean) : []

  /* ---------------- AI 深度分析 ---------------- */

  const runAnalysis = async () => {
    const seq = seqRef.current
    setAnalysis({ status: 'loading', error: '', payload: null })
    try {
      const focus = readField(settings || {}, 'analysisFocus', 'analysis_focus') || 'balanced'
      const payload = await listingService.runDetailAnalysis(listingId, { focus })
      if (seq !== seqRef.current) return
      if (payload?.ok === false) throw new Error(payload?.summary || 'AI 分析返回失败')
      setAnalysis({ status: 'ready', error: '', payload })
    } catch (err) {
      if (seq !== seqRef.current) return
      setAnalysis({ status: 'error', error: err?.message || 'AI 分析请求失败', payload: null })
    }
  }

  /* ---------------- 收藏 ---------------- */

  const toggleFavorite = async () => {
    if (favoriteBusy) return
    setFavoriteBusy(true)
    setFavoriteError('')
    try {
      if (favorite) {
        await userService.removeFavorite(listingId)
        setFavorite(false)
      } else {
        await userService.saveFavorite({
          listingId,
          listing: {
            id: listingId,
            title: detail.title,
            price: detail.price,
            location: detail.address,
            image: detail.image || '',
          },
        })
        setFavorite(true)
      }
    } catch (err) {
      setFavoriteError(err?.message || '收藏操作失败')
    } finally {
      setFavoriteBusy(false)
    }
  }

  /* ---------------- 渲染 ---------------- */

  if (detailState.status === 'loading') {
    return (
      <div className="min-h-screen bg-ink-50">
        <PageHeader backTo={backTo} lastCity={lastCity} />
        <LoadingBlock text="正在读取房源详情…" className="py-32" />
      </div>
    )
  }

  if (detailState.status === 'error') {
    return (
      <div className="min-h-screen bg-ink-50">
        <PageHeader backTo={backTo} lastCity={lastCity} />
        <EmptyState
          icon="🏚️"
          title="房源详情加载失败"
          description={detailState.error}
          action={
            <div className="flex gap-2">
              <Button variant="secondary" size="sm" onClick={loadDetail}>
                重试
              </Button>
              <Button size="sm" onClick={() => window.history.back()}>
                返回上一页
              </Button>
            </div>
          }
          className="py-32"
        />
      </div>
    )
  }

  const specs = [
    { label: '户型', value: detail.layout },
    { label: '卫浴', value: detail.baths },
    { label: '面积', value: detail.size },
    { label: '楼层', value: detail.floor },
  ]

  return (
    <div className="min-h-screen bg-ink-50 pb-24">
      <PageHeader backTo={backTo} lastCity={lastCity} />

      <main className="mx-auto max-w-7xl px-4 py-6 sm:px-6">
        {/* 图集 + 信息卡 */}
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
          <div className="min-w-0 animate-fade-up">
            <Gallery title={detail.title} images={detail.images} />
          </div>

          <aside className="animate-fade-up">
            <section className="rounded-2xl bg-surface p-5 shadow-card ring-1 ring-white/[0.06] lg:sticky lg:top-20">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <h1 className="text-lg font-semibold leading-7 text-ink-900">{detail.title}</h1>
                  {detail.address && <p className="mt-1 text-xs leading-5 text-ink-400">{detail.address}</p>}
                  {(() => {
                    const vb = verificationBadge(detail.verified)
                    return vb ? (
                      <div className="mt-2">
                        <Badge tone={vb.tone}>
                          <span title={vb.title}>{vb.icon} {vb.label}</span>
                        </Badge>
                      </div>
                    ) : null
                  })()}
                </div>
                <button
                  type="button"
                  onClick={toggleFavorite}
                  disabled={favoriteBusy}
                  aria-label={favorite ? '取消收藏' : '收藏房源'}
                  aria-pressed={favorite}
                  className={[
                    'shrink-0 rounded-full p-2 ring-1 transition disabled:opacity-50',
                    favorite
                      ? 'bg-rose-50 text-rose-500 ring-rose-100'
                      : 'text-ink-400 ring-ink-200 hover:bg-rose-50 hover:text-rose-400',
                  ].join(' ')}
                >
                  <svg
                    viewBox="0 0 24 24"
                    fill={favorite ? 'currentColor' : 'none'}
                    stroke="currentColor"
                    strokeWidth="1.5"
                    className="h-5 w-5"
                    aria-hidden="true"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      d="M21 8.25c0-2.485-2.099-4.5-4.688-4.5-1.935 0-3.597 1.126-4.312 2.733-.715-1.607-2.377-2.733-4.313-2.733C5.1 3.75 3 5.765 3 8.25c0 7.22 9 12 9 12s9-4.78 9-12Z"
                    />
                  </svg>
                </button>
              </div>
              {favoriteError && <p className="mt-2 text-xs text-rose-600">{favoriteError}</p>}

              <div className="mt-4 flex items-end justify-between gap-3">
                <div>
                  <p className="text-3xl font-semibold tracking-tight text-ink-900">{detail.price}</p>
                  {detail.availability && <p className="mt-1 text-xs text-ink-500">{detail.availability}</p>}
                </div>
                <ScoreRing score={detail.score} size={84} />
              </div>

              <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-ink-100 pt-4">
                {specs.map((spec) => (
                  <div key={spec.label} className="rounded-xl bg-ink-50 px-3 py-2">
                    <dt className="text-[10px] font-medium uppercase tracking-wide text-ink-400">{spec.label}</dt>
                    <dd className="mt-0.5 truncate text-sm font-medium text-ink-800">{spec.value}</dd>
                  </div>
                ))}
              </dl>

              {detail.sourceLabel && (
                <div className="mt-4 flex items-center gap-2">
                  <Badge tone="info">来源：{detail.sourceLabel}</Badge>
                </div>
              )}
            </section>
          </aside>
        </div>

        {/* 评估卡区 */}
        {(detail.valueIndex || detail.environmentEvaluation || detail.commuteEvaluation) && (
          <div className="mt-6">
            <EvaluationCards
              valueIndex={detail.valueIndex}
              environmentEvaluation={detail.environmentEvaluation}
              commuteEvaluation={detail.commuteEvaluation}
            />
          </div>
        )}

        {/* AI 深度分析 + 洞察 */}
        <section className="mt-6 rounded-2xl bg-surface p-5 shadow-card ring-1 ring-white/[0.06]" aria-label="AI 深度分析">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <h2 className="text-sm font-semibold text-ink-900">AI 深度分析</h2>
              <p className="mt-0.5 text-xs text-ink-400">
                结合通勤、周边配套与价格数据补全评估；已有缓存时不会重复计算。
              </p>
            </div>
            <div className="flex items-center gap-2">
              {analysis.payload?.cacheHit && <Badge tone="neutral">缓存结果</Badge>}
              <Button size="sm" onClick={runAnalysis} loading={analysis.status === 'loading'}>
                {analysis.status === 'ready' ? '重新分析' : '运行 AI 分析'}
              </Button>
            </div>
          </div>

          {analysis.status === 'loading' && (
            <div className="mt-4 space-y-2" aria-live="polite">
              <p className="text-xs text-ink-500">正在调用分析 Agent，补全通勤与周边数据…</p>
              <div className="animate-pulse space-y-2">
                <div className="h-3 w-full rounded bg-ink-100" />
                <div className="h-3 w-4/5 rounded bg-ink-100" />
                <div className="h-3 w-3/5 rounded bg-ink-100" />
              </div>
            </div>
          )}

          {analysis.status === 'error' && (
            <div className="mt-4 rounded-xl bg-rose-50 p-3 text-xs leading-5 text-rose-700 ring-1 ring-rose-100">
              <p>{analysis.error}</p>
              <Button variant="secondary" size="sm" className="mt-2" onClick={runAnalysis}>
                重试
              </Button>
            </div>
          )}

          {analysis.status === 'ready' && analysisMeta && (
            <div className="mt-4 flex flex-wrap items-center gap-1.5" aria-label="分析元信息">
              {analysisMeta.model && <Badge tone="brand">{analysisMeta.model}</Badge>}
              {(analysisMeta.agentMode || analysisMeta.mode) && (
                <Badge tone="info">{analysisMeta.agentMode || analysisMeta.mode}</Badge>
              )}
              {analysisMeta.status && (
                <Badge tone={analysisMeta.status === 'ready' ? 'success' : 'warning'}>{analysisMeta.status}</Badge>
              )}
              {analysisMeta.computedAt && (
                <span className="text-xs text-ink-400">计算于 {formatDate(analysisMeta.computedAt)}</span>
              )}
            </div>
          )}

          {analysisWarnings.length > 0 && (
            <ul className="mt-3 space-y-1 rounded-xl bg-amber-50 px-3 py-2.5 text-xs leading-5 text-amber-800 ring-1 ring-amber-100">
              {analysisWarnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
            </ul>
          )}

          {detail.insight && <p className="mt-4 text-sm leading-7 text-ink-700">{detail.insight}</p>}

          {(detail.pros.length > 0 || detail.cons.length > 0) && (
            <div className="mt-4 grid gap-4 border-t border-ink-100 pt-4 sm:grid-cols-2">
              <div>
                <h3 className="text-xs font-semibold uppercase tracking-wide text-emerald-600">优势</h3>
                <ul className="mt-2 space-y-1.5">
                  {detail.pros.map((item) => (
                    <li key={item} className="flex items-start gap-2 text-sm leading-6 text-ink-700">
                      <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-emerald-500" aria-hidden="true" />
                      {item}
                    </li>
                  ))}
                </ul>
              </div>
              <div>
                <h3 className="text-xs font-semibold uppercase tracking-wide text-rose-500">注意事项</h3>
                <ul className="mt-2 space-y-1.5">
                  {detail.cons.map((item) => (
                    <li key={item} className="flex items-start gap-2 text-sm leading-6 text-ink-700">
                      <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-rose-400" aria-hidden="true" />
                      {item}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          )}
        </section>

        {/* 通勤地图 + 通勤条目 */}
        {(detail.commuteMap || detail.commute.length > 0) && (
          <section className="mt-6 rounded-2xl bg-surface p-5 shadow-card ring-1 ring-white/[0.06]" aria-label="通勤信息">
            <h2 className="text-sm font-semibold text-ink-900">通勤与周边</h2>
            <div className="mt-3 grid gap-4 lg:grid-cols-[minmax(0,1fr)_320px]">
              <div className="min-w-0">
                {detail.commuteMap ? (
                  <CommuteMapCard commuteMap={detail.commuteMap} />
                ) : (
                  <EmptyState
                    icon="🗺️"
                    title="暂无通勤地图数据"
                    description="运行 AI 深度分析后可补全目标点与周边配套。"
                    className="py-8"
                  />
                )}
              </div>
              {detail.commute.length > 0 && (
                <ul className="space-y-2">
                  {detail.commute.map((item, index) => (
                    <li
                      key={`${item.label}-${index}`}
                      className="flex items-center justify-between gap-2 rounded-xl bg-ink-50 px-3 py-2.5"
                    >
                      <span className="flex min-w-0 items-center gap-2 text-sm text-ink-700">
                        <CommuteIcon name={item.icon} />
                        <span className="truncate">{item.label}</span>
                      </span>
                      <span
                        className={[
                          'shrink-0 text-sm font-medium',
                          item.tone === 'primary' ? 'text-brand-300' : 'text-ink-500',
                        ].join(' ')}
                      >
                        {item.value}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>
        )}

        {/* 数据来源 */}
        {detail.dataSource && (
          <div className="mt-6">
            <DataSourceCard dataSource={detail.dataSource} />
          </div>
        )}
      </main>

      {/* 房源问答浮动按钮 + 抽屉 */}
      <button
        type="button"
        onClick={() => setChatOpen(true)}
        aria-label="打开房源问答"
        className="shine fixed bottom-6 right-6 z-40 flex items-center gap-2 overflow-hidden rounded-full bg-brand-gradient px-5 py-3 text-sm font-medium text-white shadow-glow-lg transition duration-200 hover:-translate-y-0.5 hover:brightness-110"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-4 w-4" aria-hidden="true">
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M8.625 12a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0H8.25m4.125 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0H12m4.125 0a.375.375 0 1 1-.75 0 .375.375 0 0 1 .75 0Zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 0 1-2.555-.337A5.972 5.972 0 0 1 5.41 20.97a5.969 5.969 0 0 1-.474-.065 4.48 4.48 0 0 0 .978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25Z"
          />
        </svg>
        房源问答
      </button>

      <ChatDrawer
        listingId={String(listingId || '')}
        open={chatOpen}
        onClose={() => setChatOpen(false)}
        contextTitle={detail.title}
        contextMeta={detail.price}
      />
    </div>
  )
}

/** 顶部面包屑：返回工作台 + 品牌 */
function PageHeader({ backTo, lastCity }) {
  return (
    <header className="sticky top-0 z-30 border-b border-white/[0.06] bg-surface-deep/70 backdrop-blur-xl">
      <div className="mx-auto flex h-14 max-w-7xl items-center justify-between gap-3 px-4 sm:px-6">
        <nav className="flex min-w-0 items-center gap-2 text-sm" aria-label="面包屑">
          <Link
            to={backTo}
            className="flex items-center gap-1.5 rounded-full px-2.5 py-1.5 font-medium text-ink-600 transition hover:bg-ink-100 hover:text-ink-900"
          >
            <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
              <path
                fillRule="evenodd"
                d="M17 10a.75.75 0 0 1-.75.75H5.612l4.158 3.96a.75.75 0 1 1-1.04 1.08l-5.5-5.25a.75.75 0 0 1 0-1.08l5.5-5.25a.75.75 0 1 1 1.04 1.08L5.612 9.25H16.25A.75.75 0 0 1 17 10Z"
                clipRule="evenodd"
              />
            </svg>
            返回{lastCity ? `${lastCity}工作台` : '城市选择'}
          </Link>
          <span className="text-ink-300" aria-hidden="true">
            /
          </span>
          <span className="truncate text-ink-400">房源详情</span>
        </nav>
        <Link to="/" className="flex shrink-0 items-center gap-2" aria-label="Renti Agent 首页">
          <BrandMark className="h-7 w-7" />
          <span className="hidden text-sm font-semibold text-ink-900 sm:block">Renti Agent</span>
        </Link>
      </div>
    </header>
  )
}

export default PropertyDetailPage

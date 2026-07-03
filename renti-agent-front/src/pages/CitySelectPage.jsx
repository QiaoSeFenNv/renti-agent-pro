import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import Badge from '../components/ui/Badge.jsx'
import Button from '../components/ui/Button.jsx'
import { EmptyState, LoadingBlock } from '../components/ui/Feedback.jsx'
import SiteLayout from '../layouts/SiteLayout.jsx'
import { cityService } from '../services/cityService.js'

const PAGE_SIZE = 24

const DEFAULT_MODE_OPTIONS = [
  {
    value: 'system_search',
    label: '查找平台房源',
    description: '没有明确房源时使用，进入系统采集房源库做地图查询。',
  },
  {
    value: 'user_import',
    label: '导入自有房源',
    description: '已有候选房源时使用，只分析自己导入的数据，不混入平台房源。',
  },
]

/**
 * 城市选择页：搜索 + 分页城市网格 + 工作台模式选择。
 * 选定 enabled 城市后跳 /city/:name?mode=xxx。
 */
function CitySelectPage() {
  const navigate = useNavigate()

  const [query, setQuery] = useState('')
  const [debouncedQuery, setDebouncedQuery] = useState('')
  const [page, setPage] = useState(1)

  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [cities, setCities] = useState([])
  const [title, setTitle] = useState('选择您的目标城市')
  const [notice, setNotice] = useState('更多城市正在陆续加入覆盖范围')
  const [totalPages, setTotalPages] = useState(1)
  const [modeOptions, setModeOptions] = useState(DEFAULT_MODE_OPTIONS)
  const [mode, setMode] = useState(
    () => localStorage.getItem('renti.workspaceMode') || 'system_search',
  )

  const requestSeq = useRef(0)

  // 搜索防抖 300ms
  useEffect(() => {
    const timer = setTimeout(() => {
      setDebouncedQuery(query.trim())
      setPage(1)
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  const loadCities = useCallback(async () => {
    const seq = ++requestSeq.current
    setLoading(true)
    setError('')
    try {
      const data = await cityService.getCities({ query: debouncedQuery, page, limit: PAGE_SIZE })
      if (seq !== requestSeq.current) return
      const list = Array.isArray(data?.cities) ? data.cities : Array.isArray(data?.items) ? data.items : []
      setCities(list)
      if (data?.title) setTitle(data.title)
      if (data?.notice) setNotice(data.notice)
      setTotalPages(Number(data?.totalPages) || 1)
      if (Array.isArray(data?.modeOptions) && data.modeOptions.length > 0) {
        setModeOptions(data.modeOptions)
      }
    } catch (err) {
      if (seq !== requestSeq.current) return
      setError(err.message || '城市列表加载失败')
      setCities([])
    } finally {
      if (seq === requestSeq.current) setLoading(false)
    }
  }, [debouncedQuery, page])

  useEffect(() => {
    loadCities()
  }, [loadCities])

  const isEnabled = (city) => city?.enabled ?? city?.status === 'available'

  const handleSelectCity = (city) => {
    if (!isEnabled(city)) return
    localStorage.setItem('renti.lastCity', city.name)
    localStorage.setItem('renti.workspaceMode', mode)
    navigate(`/city/${encodeURIComponent(city.name)}?mode=${mode}`)
  }

  const handleSelectMode = (value) => {
    setMode(value)
    localStorage.setItem('renti.workspaceMode', value)
  }

  return (
    <SiteLayout>
      <div className="mx-auto max-w-7xl px-4 py-10 sm:px-6">
        {/* 标题与说明 */}
        <div className="animate-fade-up">
          <h1 className="text-3xl font-semibold tracking-tight text-ink-900">{title}</h1>
          <p className="mt-2 text-sm text-ink-500">{notice}</p>
        </div>

        {/* 搜索框 */}
        <div className="mt-6 max-w-md">
          <label htmlFor="city-search" className="sr-only">
            搜索城市
          </label>
          <div className="relative">
            <svg
              viewBox="0 0 20 20"
              fill="currentColor"
              className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-ink-400"
              aria-hidden="true"
            >
              <path
                fillRule="evenodd"
                d="M9 3.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11ZM2 9a7 7 0 1 1 12.45 4.4l3.07 3.08a.75.75 0 1 1-1.06 1.06l-3.07-3.07A7 7 0 0 1 2 9Z"
                clipRule="evenodd"
              />
            </svg>
            <input
              id="city-search"
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索城市名称 / 拼音 / 省份"
              className="h-11 w-full rounded-full border-0 bg-white pl-10 pr-4 text-sm text-ink-900 shadow-sm ring-1 ring-inset ring-ink-200 transition placeholder:text-ink-300 focus:ring-2 focus:ring-brand-500"
            />
          </div>
        </div>

        {/* 城市网格 */}
        <div className="mt-6">
          {loading ? (
            <LoadingBlock text="正在加载城市列表…" />
          ) : error ? (
            <EmptyState
              icon="⚠️"
              title="城市列表加载失败"
              description={error}
              action={
                <Button variant="secondary" size="sm" onClick={loadCities}>
                  重试
                </Button>
              }
            />
          ) : cities.length === 0 ? (
            <EmptyState title="没有匹配的城市" description="换个关键词试试，或清空搜索查看全部城市。" />
          ) : (
            <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
              {cities.map((city) => {
                const enabled = isEnabled(city)
                return (
                  <li key={city.name}>
                    <button
                      type="button"
                      onClick={() => handleSelectCity(city)}
                      disabled={!enabled}
                      aria-label={enabled ? `进入${city.name}工作台` : `${city.name} 即将开放`}
                      className={[
                        'relative w-full rounded-2xl bg-white p-4 text-left shadow-card ring-1 transition',
                        enabled
                          ? 'ring-ink-100/60 hover:-translate-y-0.5 hover:shadow-float hover:ring-brand-200'
                          : 'cursor-not-allowed ring-ink-100/60 opacity-60',
                      ].join(' ')}
                    >
                      {!enabled && (
                        <span className="absolute right-3 top-3">
                          <Badge tone="warning">即将开放</Badge>
                        </span>
                      )}
                      <p className={['text-base font-semibold', enabled ? 'text-ink-900' : 'text-ink-500'].join(' ')}>
                        {city.name}
                      </p>
                      <p className="mt-1 truncate text-xs text-ink-400">
                        {[city.nameEn || city.en, city.province].filter(Boolean).join(' · ') || '—'}
                      </p>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </div>

        {/* 分页 */}
        {!loading && !error && totalPages > 1 && (
          <div className="mt-6 flex items-center justify-center gap-3">
            <Button
              variant="secondary"
              size="sm"
              disabled={page <= 1}
              onClick={() => setPage((current) => Math.max(1, current - 1))}
              aria-label="上一页"
            >
              上一页
            </Button>
            <span className="text-sm text-ink-500">
              {page} / {totalPages}
            </span>
            <Button
              variant="secondary"
              size="sm"
              disabled={page >= totalPages}
              onClick={() => setPage((current) => Math.min(totalPages, current + 1))}
              aria-label="下一页"
            >
              下一页
            </Button>
          </div>
        )}

        {/* 模式选择 */}
        <section className="mt-12" aria-label="工作台模式选择">
          <h2 className="text-sm font-semibold text-ink-900">选择工作台模式</h2>
          <p className="mt-1 text-xs text-ink-400">进入城市后仍可切换，选择将保存为默认偏好。</p>
          <div className="mt-4 grid gap-3 sm:grid-cols-2 sm:max-w-2xl">
            {modeOptions.map((option) => {
              const active = mode === option.value
              return (
                <button
                  key={option.value}
                  type="button"
                  onClick={() => handleSelectMode(option.value)}
                  aria-pressed={active}
                  aria-label={`选择模式：${option.label}`}
                  className={[
                    'rounded-2xl bg-white p-4 text-left shadow-card ring-1 transition',
                    active
                      ? 'ring-2 ring-brand-500'
                      : 'ring-ink-100/60 hover:ring-brand-200',
                  ].join(' ')}
                >
                  <div className="flex items-center justify-between gap-2">
                    <p className="text-sm font-semibold text-ink-900">{option.label}</p>
                    {active && <Badge tone="brand">当前</Badge>}
                  </div>
                  <p className="mt-1.5 text-xs leading-5 text-ink-500">{option.description}</p>
                </button>
              )
            })}
          </div>
        </section>
      </div>
    </SiteLayout>
  )
}

export default CitySelectPage

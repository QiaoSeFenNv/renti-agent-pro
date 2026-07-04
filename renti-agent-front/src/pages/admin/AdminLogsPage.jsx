import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import { EmptyState, LoadingBlock } from '../../components/ui/Feedback.jsx'
import FilterBar, { FilterInput, FilterSelect } from '../../features/admin/components/FilterBar.jsx'
import JsonBlock from '../../features/admin/components/JsonBlock.jsx'
import { ErrorBar } from '../../features/admin/components/Notice.jsx'
import Pagination from '../../features/admin/components/Pagination.jsx'
import { useAsyncData } from '../../features/admin/hooks.js'
import { extractItems, formatTime, pick, read } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

const KIND_TABS = [
  { value: 'api', label: '接口日志' },
  { value: 'app', label: '应用日志' },
  { value: 'llm', label: 'LLM 日志' },
]

const LEVEL_TONES = {
  ERROR: 'danger',
  WARNING: 'warning',
  WARN: 'warning',
  INFO: 'info',
  DEBUG: 'neutral',
}

const PAGE_SIZE = 50

/** 从 data 对象拼日志摘要（对齐旧版 logSummary） */
function logSummary(entry) {
  const message = read(entry, 'message')
  if (message) return message
  const data = read(entry, 'data') ?? {}
  const parts = []
  const method = read(data, 'method')
  const path = read(data, 'path')
  if (method || path) parts.push(`${method ?? ''} ${path ?? ''}`.trim())
  const statusCode = read(data, 'statusCode')
  if (statusCode !== undefined) parts.push(`HTTP ${statusCode}`)
  const model = read(data, 'model')
  if (model) parts.push(String(model))
  const status = read(data, 'status')
  if (status) parts.push(String(status))
  const durationMs = read(data, 'durationMs')
  if (durationMs !== undefined) parts.push(`${durationMs}ms`)
  if (parts.length > 0) return parts.join(' · ')
  return read(entry, 'line') ?? read(entry, 'raw') ?? '—'
}

function LogRow({ entry }) {
  const [open, setOpen] = useState(false)
  const level = String(read(entry, 'level', 'INFO')).toUpperCase()
  const data = read(entry, 'data')
  const hasPayload = data && Object.keys(data).length > 0

  return (
    <li className="px-4 py-3 transition-colors hover:bg-white/[0.04]">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="w-36 shrink-0 text-xs tabular-nums text-ink-400">
          {formatTime(read(entry, 'timestamp') ?? read(entry, 'createdAt'))}
        </span>
        <Badge tone={LEVEL_TONES[level] ?? 'neutral'}>{level}</Badge>
        {read(entry, 'event') && (
          <span className="text-xs font-medium text-ink-500">{read(entry, 'event')}</span>
        )}
        <span className="min-w-0 flex-1 break-all text-sm text-ink-800">{logSummary(entry)}</span>
        {hasPayload && (
          <button
            type="button"
            className="shrink-0 text-xs font-medium text-brand-300 hover:text-brand-200"
            onClick={() => setOpen((value) => !value)}
          >
            {open ? '收起' : '展开'}
          </button>
        )}
      </div>
      {open && hasPayload && <JsonBlock value={data} className="mt-2" maxHeight="max-h-72" />}
    </li>
  )
}

/**
 * 系统日志：kind Tab（api/app/llm）+ level/query 筛选 + 分页。
 */
function AdminLogsPage() {
  const [kind, setKind] = useState('api')
  const [level, setLevel] = useState('')
  const [queryInput, setQueryInput] = useState('')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(1)

  const { loading, error, data, reload } = useAsyncData(
    () =>
      adminService.getLogs({
        kind,
        level: level || undefined,
        query: query || undefined,
        page,
        limit: PAGE_SIZE,
      }),
    [kind, level, query, page],
  )

  const logs = extractItems(data)
  const total = pick(data, 'total')
  const totalPages = pick(data, 'totalPages', 'total_pages')
  const file = pick(data, 'file')

  return (
    <div className="space-y-4 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">系统日志</h1>
        <p className="mt-1 text-sm text-ink-400">API / 应用 / LLM 调用日志查询{file ? ` · ${file}` : ''}</p>
      </div>

      {/* kind Tab */}
      <div className="flex gap-1 rounded-full bg-black/30 p-1 ring-1 ring-inset ring-white/[0.06]" role="tablist" aria-label="日志类型">
        {KIND_TABS.map((tab) => (
          <button
            key={tab.value}
            type="button"
            role="tab"
            aria-selected={kind === tab.value}
            onClick={() => {
              setKind(tab.value)
              setPage(1)
            }}
            className={[
              'flex-1 rounded-full px-4 py-1.5 text-sm font-medium transition sm:flex-none',
              kind === tab.value ? 'bg-white/[0.12] text-white' : 'text-ink-500 hover:text-ink-800',
            ].join(' ')}
          >
            {tab.label}
          </button>
        ))}
      </div>

      <FilterBar
        onSubmit={() => {
          setQuery(queryInput.trim())
          setPage(1)
        }}
      >
        <FilterSelect
          label="级别"
          value={level}
          onChange={(event) => {
            setLevel(event.target.value)
            setPage(1)
          }}
          className="w-36"
        >
          <option value="">全部级别</option>
          <option value="INFO">INFO</option>
          <option value="WARNING">WARNING</option>
          <option value="ERROR">ERROR</option>
        </FilterSelect>
        <FilterInput
          label="搜索"
          placeholder="按路径、模型、工具、状态搜索"
          value={queryInput}
          onChange={(event) => setQueryInput(event.target.value)}
          className="w-64"
        />
        <button
          type="submit"
          className="h-9 rounded-full bg-brand-600 px-4 text-sm font-medium text-white transition hover:bg-brand-700"
        >
          查询
        </button>
      </FilterBar>

      {error && <ErrorBar error={error} onRetry={reload} />}

      <div className="overflow-hidden rounded-2xl bg-surface shadow-card ring-1 ring-white/[0.06]">
        {loading ? (
          <LoadingBlock text="正在加载日志…" className="py-12" />
        ) : logs.length === 0 ? (
          <EmptyState title="暂无日志" description="当前筛选条件下没有匹配的日志记录" className="py-12" />
        ) : (
          <ul className="divide-y divide-white/[0.06]">
            {logs.map((entry, index) => (
              <LogRow key={read(entry, 'id') ?? index} entry={entry} />
            ))}
          </ul>
        )}
        {!loading && logs.length > 0 && (
          <Pagination
            page={page}
            totalPages={totalPages}
            total={total}
            hasMore={logs.length >= PAGE_SIZE}
            onChange={setPage}
            className="border-t border-white/[0.06]"
          />
        )}
      </div>
    </div>
  )
}

export default AdminLogsPage

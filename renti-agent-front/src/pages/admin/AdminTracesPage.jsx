import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import { LoadingBlock } from '../../components/ui/Feedback.jsx'
import DataTable from '../../features/admin/components/DataTable.jsx'
import Drawer from '../../features/admin/components/Drawer.jsx'
import FilterBar, { FilterInput, FilterSelect } from '../../features/admin/components/FilterBar.jsx'
import JsonBlock from '../../features/admin/components/JsonBlock.jsx'
import { ErrorBar } from '../../features/admin/components/Notice.jsx'
import Pagination from '../../features/admin/components/Pagination.jsx'
import { useAsyncData } from '../../features/admin/hooks.js'
import { extractItems, formatMs, formatTime, pick, read, truncate } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

const PAGE_SIZE = 20

const STATUS_TONES = {
  ok: 'success',
  success: 'success',
  completed: 'success',
  error: 'danger',
  failed: 'danger',
  fallback: 'warning',
  partial: 'warning',
}

function statusTone(status) {
  return STATUS_TONES[String(status ?? '').toLowerCase()] ?? 'neutral'
}

/** 步骤时间线：steps（兜底 toolTrace）纵向展示 */
function StepTimeline({ steps }) {
  if (!steps || steps.length === 0) {
    return <p className="text-xs text-ink-400">（无步骤记录）</p>
  }
  return (
    <ol className="relative space-y-4 border-l border-ink-100 pl-5">
      {steps.map((step, index) => {
        const status = read(step, 'status')
        const tone = statusTone(status)
        const dotColor =
          tone === 'success' ? 'bg-emerald-500' : tone === 'danger' ? 'bg-rose-500' : tone === 'warning' ? 'bg-amber-500' : 'bg-ink-300'
        return (
          <li key={index} className="relative">
            <span className={['absolute -left-[26px] top-1 h-2.5 w-2.5 rounded-full ring-4 ring-white', dotColor].join(' ')} aria-hidden="true" />
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-sm font-medium text-ink-900">
                {read(step, 'tool') ?? read(step, 'name') ?? read(step, 'node') ?? `步骤 ${index + 1}`}
              </span>
              {status && <Badge tone={tone}>{String(status)}</Badge>}
              {read(step, 'durationMs') !== undefined && (
                <span className="text-xs tabular-nums text-ink-400">{formatMs(read(step, 'durationMs'))}</span>
              )}
            </div>
            {read(step, 'summary') && (
              <p className="mt-1 break-all text-xs leading-5 text-ink-500">{read(step, 'summary')}</p>
            )}
          </li>
        )
      })}
    </ol>
  )
}

function TraceDrawer({ traceId, onClose }) {
  const { loading, error, data, reload } = useAsyncData(
    () => adminService.getAgentTraceDetail(traceId),
    [traceId],
  )
  const trace = pick(data, 'trace') ?? data ?? {}
  const steps = read(trace, 'steps') ?? read(trace, 'toolTrace') ?? []

  const resultPayload = {
    summary: read(trace, 'summary'),
    intent: read(trace, 'intent'),
    usage: read(trace, 'usage'),
    requestPayload: read(trace, 'requestPayload'),
    errorMessage: read(trace, 'errorMessage'),
  }

  return (
    <Drawer
      open
      onClose={onClose}
      title="Trace 详情"
      subtitle={`#${traceId} · ${read(trace, 'model') ?? read(trace, 'provider') ?? ''}`}
      size="lg"
    >
      {loading && <LoadingBlock text="正在加载 Trace…" />}
      {error && <ErrorBar error={error} onRetry={reload} />}
      {!loading && !error && (
        <div className="space-y-6">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <Badge tone={statusTone(read(trace, 'status'))}>{read(trace, 'status') ?? 'unknown'}</Badge>
            {read(trace, 'agentMode') && <Badge tone="brand">{read(trace, 'agentMode')}</Badge>}
            {read(trace, 'city') && <Badge tone="neutral">{read(trace, 'city')}</Badge>}
            <span className="text-xs text-ink-400">
              {formatTime(read(trace, 'createdAt'))} · 耗时 {formatMs(read(trace, 'durationMs'))} · 结果{' '}
              {read(trace, 'resultCount', 0)} 条
            </span>
          </div>

          {read(trace, 'requestText') && (
            <div className="rounded-xl bg-ink-50 px-4 py-3 text-sm leading-6 text-ink-700">
              {read(trace, 'requestText')}
            </div>
          )}

          {read(trace, 'fallbackReason') && (
            <p className="rounded-xl bg-amber-50 px-4 py-2.5 text-xs text-amber-700 ring-1 ring-inset ring-amber-200">
              回退原因：{read(trace, 'fallbackReason')}
            </p>
          )}

          <section>
            <h4 className="mb-3 text-xs font-semibold uppercase tracking-wide text-ink-400">执行步骤</h4>
            <StepTimeline steps={steps} />
          </section>

          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-400">结果数据</h4>
            <JsonBlock value={resultPayload} />
          </section>
        </div>
      )}
    </Drawer>
  )
}

/**
 * Agent 追踪：执行链路列表 + 步骤时间线详情。
 */
function AdminTracesPage() {
  const [status, setStatus] = useState('')
  const [mode, setMode] = useState('')
  const [userIdInput, setUserIdInput] = useState('')
  const [userId, setUserId] = useState('')
  const [page, setPage] = useState(1)
  const [selectedId, setSelectedId] = useState(null)

  const { loading, error, data, reload } = useAsyncData(
    () =>
      adminService.getAgentTraces({
        page,
        limit: PAGE_SIZE,
        status: status || undefined,
        mode: mode || undefined,
        userId: userId || undefined,
      }),
    [page, status, mode, userId],
  )

  const traces = extractItems(data)
  const total = pick(data, 'total')
  const totalPages = pick(data, 'totalPages', 'total_pages')

  const columns = [
    { key: 'createdAt', label: '时间', className: 'w-40', render: (row) => formatTime(read(row, 'createdAt')) },
    { key: 'userId', label: '用户', className: 'w-16 tabular-nums', render: (row) => read(row, 'userId') ?? '—' },
    {
      key: 'agentMode',
      label: '模式',
      render: (row) => <Badge tone="brand">{read(row, 'agentMode') ?? read(row, 'workspaceMode') ?? '—'}</Badge>,
    },
    {
      key: 'status',
      label: '状态',
      render: (row) => <Badge tone={statusTone(read(row, 'status'))}>{read(row, 'status') ?? '—'}</Badge>,
    },
    {
      key: 'requestText',
      label: '请求内容',
      render: (row) => <span className="text-ink-700">{truncate(read(row, 'requestText'), 48)}</span>,
    },
    {
      key: 'resultCount',
      label: '结果 / 耗时',
      align: 'right',
      render: (row) => (
        <span className="tabular-nums text-ink-500">
          {read(row, 'resultCount', 0)} 条 · {formatMs(read(row, 'durationMs'))}
        </span>
      ),
    },
  ]

  return (
    <div className="space-y-4 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">Agent 追踪</h1>
        <p className="mt-1 text-sm text-ink-400">Agent 执行链路与步骤时间线</p>
      </div>

      <FilterBar
        onSubmit={() => {
          setUserId(userIdInput.trim())
          setPage(1)
        }}
      >
        <FilterSelect
          label="状态"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value)
            setPage(1)
          }}
          className="w-36"
        >
          <option value="">全部状态</option>
          <option value="ok">ok</option>
          <option value="error">error</option>
          <option value="fallback">fallback</option>
        </FilterSelect>
        <FilterSelect
          label="模式"
          value={mode}
          onChange={(event) => {
            setMode(event.target.value)
            setPage(1)
          }}
          className="w-40"
        >
          <option value="">全部模式</option>
          <option value="system_search">system_search</option>
          <option value="user_import">user_import</option>
        </FilterSelect>
        <FilterInput
          label="用户 ID"
          placeholder="按用户 ID 过滤"
          value={userIdInput}
          onChange={(event) => setUserIdInput(event.target.value)}
          className="w-40"
        />
        <button
          type="submit"
          className="h-9 rounded-full bg-brand-600 px-4 text-sm font-medium text-white transition hover:bg-brand-700"
        >
          查询
        </button>
      </FilterBar>

      {error && <ErrorBar error={error} onRetry={reload} />}

      <DataTable
        columns={columns}
        rows={traces}
        loading={loading}
        rowKey={(row) => read(row, 'id')}
        onRowClick={(row) => setSelectedId(read(row, 'id'))}
        emptyText="暂无 Trace 记录"
        footer={
          !loading && traces.length > 0 ? (
            <Pagination
              page={page}
              totalPages={totalPages}
              total={total}
              hasMore={traces.length >= PAGE_SIZE}
              onChange={setPage}
              className="border-t border-ink-100"
            />
          ) : null
        }
      />

      {selectedId != null && <TraceDrawer traceId={selectedId} onClose={() => setSelectedId(null)} />}
    </div>
  )
}

export default AdminTracesPage

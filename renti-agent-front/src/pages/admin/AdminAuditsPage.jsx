import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import Button from '../../components/ui/Button.jsx'
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

const ENDPOINT_OPTIONS = [
  { value: '', label: '全部端点' },
  { value: '/api/agent/rental-search', label: 'Agent 搜索' },
  { value: '/api/search/map-intent', label: '地图搜索' },
]

/** 命中列表（审计详情与回放结果共用） */
function HitList({ hits }) {
  if (!hits || hits.length === 0) return <p className="text-xs text-ink-400">（无命中）</p>
  return (
    <ul className="space-y-1.5">
      {hits.map((hit, index) => (
        <li
          key={read(hit, 'id') ?? index}
          className="flex flex-wrap items-center gap-x-2 gap-y-0.5 rounded-lg bg-ink-50 px-3 py-2 text-xs"
        >
          <span className="font-semibold text-ink-400">#{read(hit, 'rank', index + 1)}</span>
          <span className="min-w-0 flex-1 truncate font-medium text-ink-800">
            {read(hit, 'title') ?? read(hit, 'community') ?? read(hit, 'id') ?? '—'}
          </span>
          {read(hit, 'rentPrice') !== undefined && (
            <span className="text-ink-500">¥{read(hit, 'rentPrice')}/月</span>
          )}
          <span className="tabular-nums text-brand-600">
            {typeof read(hit, 'score') === 'number' ? read(hit, 'score').toFixed(4) : read(hit, 'score') ?? '-'}
          </span>
        </li>
      ))}
    </ul>
  )
}

function AuditDrawer({ auditId, onClose }) {
  const { loading, error, data, reload } = useAsyncData(
    () => adminService.getRetrievalAuditDetail(auditId),
    [auditId],
  )
  const [replaying, setReplaying] = useState(false)
  const [replayError, setReplayError] = useState(null)
  const [replay, setReplay] = useState(null)

  const audit = pick(data, 'audit') ?? data ?? {}

  const handleReplay = async () => {
    setReplaying(true)
    setReplayError(null)
    try {
      const result = await adminService.replayRetrievalAudit(auditId)
      setReplay(result)
    } catch (err) {
      setReplayError(err)
    } finally {
      setReplaying(false)
    }
  }

  return (
    <Drawer
      open
      onClose={onClose}
      title="审计详情"
      subtitle={`#${auditId} · ${read(audit, 'endpoint') ?? ''}`}
      size="lg"
      footer={(
        <Button size="sm" onClick={handleReplay} loading={replaying}>
          回放此次检索
        </Button>
      )}
    >
      {loading && <LoadingBlock text="正在加载审计详情…" />}
      {error && <ErrorBar error={error} onRetry={reload} />}
      {!loading && !error && (
        <div className="space-y-6">
          <div className="flex flex-wrap items-center gap-2 text-xs text-ink-400">
            <Badge tone="brand">{read(audit, 'endpoint') ?? '—'}</Badge>
            {read(audit, 'city') && <Badge tone="neutral">{read(audit, 'city')}</Badge>}
            <span>
              用户 {read(audit, 'userId') ?? '—'} · {formatTime(read(audit, 'createdAt'))} · 耗时{' '}
              {formatMs(read(audit, 'durationMs'))} · 命中 {read(audit, 'totalHits', 0)} 条
            </span>
          </div>

          {read(audit, 'queryText') && (
            <div className="rounded-xl bg-ink-50 px-4 py-3 text-sm leading-6 text-ink-700">
              {read(audit, 'queryText')}
            </div>
          )}

          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-400">命中列表</h4>
            <HitList hits={read(audit, 'hits')} />
          </section>

          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-400">请求 Payload</h4>
            <JsonBlock value={read(audit, 'requestPayload')} maxHeight="max-h-64" />
          </section>

          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-400">响应摘要</h4>
            <JsonBlock value={read(audit, 'responseSummary') ?? read(audit, 'responsePayload')} maxHeight="max-h-64" />
          </section>

          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-400">Tool Trace</h4>
            <JsonBlock value={read(audit, 'toolTrace')} maxHeight="max-h-64" />
          </section>

          {replayError && <ErrorBar error={replayError} />}
          {replay && (
            <section className="rounded-2xl bg-brand-50/60 p-4 ring-1 ring-brand-100">
              <h4 className="text-xs font-semibold uppercase tracking-wide text-brand-700">回放结果对比</h4>
              <p className="mt-2 text-sm text-ink-700">
                原 <span className="font-semibold">{read(replay, 'originalTotalHits', 0)}</span> 条 · 新{' '}
                <span className="font-semibold">{read(replay, 'replayTotalHits', 0)}</span> 条 · Top 重合{' '}
                <span className="font-semibold">{read(replay, 'overlapCount', 0)}</span> 条 · 耗时{' '}
                {formatMs(read(replay, 'durationMs'))}
              </p>
              {read(replay, 'summary') && (
                <p className="mt-1 text-xs leading-5 text-ink-500">{read(replay, 'summary')}</p>
              )}
              <div className="mt-3">
                <h5 className="mb-2 text-xs font-medium text-ink-500">回放命中</h5>
                <HitList hits={read(replay, 'hits')} />
              </div>
              <div className="mt-3">
                <h5 className="mb-2 text-xs font-medium text-ink-500">新增 / 掉出 命中 ID</h5>
                <JsonBlock
                  value={{
                    newHitIds: read(replay, 'newHitIds'),
                    droppedHitIds: read(replay, 'droppedHitIds'),
                  }}
                  maxHeight="max-h-48"
                />
              </div>
            </section>
          )}
        </div>
      )}
    </Drawer>
  )
}

/**
 * 检索审计：召回明细列表 + 详情/回放。
 */
function AdminAuditsPage() {
  const [endpoint, setEndpoint] = useState('')
  const [userIdInput, setUserIdInput] = useState('')
  const [userId, setUserId] = useState('')
  const [page, setPage] = useState(1)
  const [selectedId, setSelectedId] = useState(null)

  const { loading, error, data, reload } = useAsyncData(
    () =>
      adminService.getRetrievalAudits({
        page,
        limit: PAGE_SIZE,
        endpoint: endpoint || undefined,
        userId: userId || undefined,
      }),
    [page, endpoint, userId],
  )

  const audits = extractItems(data)
  const total = pick(data, 'total')
  const totalPages = pick(data, 'totalPages', 'total_pages')

  const columns = [
    { key: 'createdAt', label: '时间', className: 'w-40', render: (row) => formatTime(read(row, 'createdAt')) },
    { key: 'userId', label: '用户', className: 'w-16 tabular-nums', render: (row) => read(row, 'userId') ?? '—' },
    {
      key: 'endpoint',
      label: '端点',
      render: (row) => <Badge tone="brand">{read(row, 'endpoint') ?? '—'}</Badge>,
    },
    {
      key: 'queryText',
      label: '查询内容',
      render: (row) => <span className="text-ink-700">{truncate(read(row, 'queryText'), 40)}</span>,
    },
    {
      key: 'durationMs',
      label: '耗时',
      align: 'right',
      render: (row) => <span className="tabular-nums text-ink-500">{formatMs(read(row, 'durationMs'))}</span>,
    },
    {
      key: 'totalHits',
      label: '命中数',
      align: 'right',
      render: (row) => <span className="tabular-nums font-medium text-ink-800">{read(row, 'totalHits', 0)}</span>,
    },
  ]

  return (
    <div className="space-y-4 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">检索审计</h1>
        <p className="mt-1 text-sm text-ink-400">召回明细、命中列表与回放对比</p>
      </div>

      <FilterBar
        onSubmit={() => {
          setUserId(userIdInput.trim())
          setPage(1)
        }}
      >
        <FilterSelect
          label="端点"
          value={endpoint}
          onChange={(event) => {
            setEndpoint(event.target.value)
            setPage(1)
          }}
          className="w-52"
        >
          {ENDPOINT_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
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
        rows={audits}
        loading={loading}
        rowKey={(row) => read(row, 'id')}
        onRowClick={(row) => setSelectedId(read(row, 'id'))}
        emptyText="暂无审计记录"
        footer={
          !loading && audits.length > 0 ? (
            <Pagination
              page={page}
              totalPages={totalPages}
              total={total}
              hasMore={audits.length >= PAGE_SIZE}
              onChange={setPage}
              className="border-t border-ink-100"
            />
          ) : null
        }
      />

      {selectedId != null && <AuditDrawer auditId={selectedId} onClose={() => setSelectedId(null)} />}
    </div>
  )
}

export default AdminAuditsPage

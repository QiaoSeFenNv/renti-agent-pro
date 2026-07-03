import { useMemo, useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import Button from '../../components/ui/Button.jsx'
import { Card, CardBody, CardHeader } from '../../components/ui/Card.jsx'
import { TextField } from '../../components/ui/Input.jsx'
import Modal from '../../components/ui/Modal.jsx'
import { EmptyState, LoadingBlock } from '../../components/ui/Feedback.jsx'
import Drawer from '../../features/admin/components/Drawer.jsx'
import { FilterSelect as StatusSelect } from '../../features/admin/components/FilterBar.jsx'
import JsonBlock from '../../features/admin/components/JsonBlock.jsx'
import { ErrorBar, SuccessBar } from '../../features/admin/components/Notice.jsx'
import Pagination from '../../features/admin/components/Pagination.jsx'
import StatCard from '../../features/admin/components/StatCard.jsx'
import { useAsyncData, useFlash } from '../../features/admin/hooks.js'
import { extractItems, formatTime, pick, read } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

const CANDIDATE_PAGE_SIZE = 10

/* ---------------- 概览统计条 ---------------- */

function OverviewStats() {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getIngestionOverview(), [])
  const counts = pick(data, 'counts') ?? {}
  const sources = pick(data, 'sources') ?? []

  if (loading) return <LoadingBlock text="正在加载采集概览…" className="py-8" />
  if (error) return <ErrorBar error={error} onRetry={reload} />

  return (
    <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-5">
      <StatCard label="待审核" value={read(counts, 'pending', 0)} tone="warning" />
      <StatCard label="已发布" value={read(counts, 'published', read(counts, 'approved', 0))} tone="success" />
      <StatCard label="已驳回" value={read(counts, 'rejected', 0)} />
      <StatCard label="疑似下架" value={read(counts, 'unavailable', 0)} tone="danger" />
      <StatCard label="数据源" value={Array.isArray(sources) ? sources.length : read(counts, 'sources', 0)} />
    </div>
  )
}

/* ---------------- 爬虫插件卡片 ---------------- */

function PluginCard({ plugin, onFlash }) {
  const pluginId = read(plugin, 'id')
  const schedule = read(plugin, 'schedule') ?? {}
  const defaults = read(plugin, 'defaultOptions') ?? {}
  const scheduleOptions = read(schedule, 'options') ?? {}
  const merged = { ...defaults, ...scheduleOptions }

  const [form, setForm] = useState(() => ({
    url: read(merged, 'url', ''),
    pages: read(merged, 'pages', 1),
    geocode: read(merged, 'geocode') !== false,
    cleanupMissing: read(merged, 'cleanupMissing') !== false,
    scheduleEnabled: Boolean(read(schedule, 'enabled')),
    intervalMinutes: read(schedule, 'intervalMinutes', 1440),
  }))
  const [running, setRunning] = useState(false)
  const [savingSchedule, setSavingSchedule] = useState(false)
  const [error, setError] = useState(null)
  const [jobResult, setJobResult] = useState(null)

  const setField = (key, value) => setForm((prev) => ({ ...prev, [key]: value }))

  const options = () => ({
    url: form.url,
    pages: Number(form.pages) || 1,
    geocode: form.geocode,
    cleanupMissing: form.cleanupMissing,
  })

  const handleRun = async () => {
    setRunning(true)
    setError(null)
    setJobResult(null)
    try {
      const result = await adminService.runCrawlerPlugin(pluginId, { options: options() })
      setJobResult(result)
      onFlash('采集任务已执行')
    } catch (err) {
      setError(err)
    } finally {
      setRunning(false)
    }
  }

  const handleSaveSchedule = async () => {
    setSavingSchedule(true)
    setError(null)
    try {
      await adminService.updateCrawlerSchedule(pluginId, {
        enabled: form.scheduleEnabled,
        intervalMinutes: Number(form.intervalMinutes) || 1440,
        options: options(),
      })
      onFlash('调度配置已保存')
    } catch (err) {
      setError(err)
    } finally {
      setSavingSchedule(false)
    }
  }

  return (
    <Card>
      <CardHeader
        title={read(plugin, 'label') ?? pluginId}
        description={read(plugin, 'description')}
        actions={(
          <div className="flex items-center gap-2">
            {read(plugin, 'city') && <Badge tone="neutral">{read(plugin, 'city')}</Badge>}
            {read(plugin, 'provider') && <Badge tone="info">{read(plugin, 'provider')}</Badge>}
          </div>
        )}
      />
      <CardBody className="space-y-4">
        {error && <ErrorBar error={error} />}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <TextField label="采集 URL" type="url" value={form.url} onChange={(e) => setField('url', e.target.value)} />
          <TextField label="抓取页数" type="number" min="1" max="10" value={form.pages} onChange={(e) => setField('pages', e.target.value)} />
        </div>
        <div className="flex flex-wrap gap-x-6 gap-y-2 text-sm text-ink-700">
          <label className="flex items-center gap-2">
            <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={form.geocode} onChange={(e) => setField('geocode', e.target.checked)} />
            使用高德补坐标
          </label>
          <label className="flex items-center gap-2">
            <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={form.cleanupMissing} onChange={(e) => setField('cleanupMissing', e.target.checked)} />
            清理本次未命中的旧房源
          </label>
        </div>

        <div className="flex flex-wrap items-end gap-3 rounded-xl bg-ink-50 p-3">
          <label className="flex items-center gap-2 text-sm text-ink-700">
            <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={form.scheduleEnabled} onChange={(e) => setField('scheduleEnabled', e.target.checked)} />
            启用定时调度
          </label>
          <label className="block w-36">
            <span className="mb-1 block text-xs font-medium text-ink-500">间隔（分钟）</span>
            <input
              type="number"
              min="15"
              max="10080"
              className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500"
              value={form.intervalMinutes}
              onChange={(e) => setField('intervalMinutes', e.target.value)}
            />
          </label>
          <Button size="sm" variant="secondary" loading={savingSchedule} onClick={handleSaveSchedule}>
            保存调度
          </Button>
          {read(schedule, 'lastRunAt') && (
            <span className="text-xs text-ink-400">
              上次：{formatTime(read(schedule, 'lastRunAt'))} · {read(schedule, 'lastSummary') ?? read(schedule, 'lastStatus') ?? ''}
            </span>
          )}
        </div>

        <div className="flex items-center gap-3">
          <Button size="sm" loading={running} onClick={handleRun}>
            运行采集
          </Button>
          {read(schedule, 'nextRunAt') && (
            <span className="text-xs text-ink-400">下次调度：{formatTime(read(schedule, 'nextRunAt'))}</span>
          )}
        </div>

        {jobResult && (
          <div>
            <h5 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-400">运行结果</h5>
            <JsonBlock value={jobResult} maxHeight="max-h-64" />
          </div>
        )}
      </CardBody>
    </Card>
  )
}

function PluginSection({ onFlash }) {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getCrawlerPlugins(), [])
  const [runningDue, setRunningDue] = useState(false)
  const [dueResult, setDueResult] = useState(null)
  const [dueError, setDueError] = useState(null)

  const plugins = pick(data, 'plugins') ?? extractItems(data)

  const handleRunDue = async () => {
    setRunningDue(true)
    setDueError(null)
    try {
      const result = await adminService.runDueCrawlerSchedules()
      setDueResult(result)
      onFlash('到期任务已触发')
      reload()
    } catch (err) {
      setDueError(err)
    } finally {
      setRunningDue(false)
    }
  }

  return (
    <section className="space-y-4">
      <div className="flex items-end justify-between gap-3">
        <h2 className="text-base font-semibold text-ink-900">爬虫插件与调度</h2>
        <Button size="sm" variant="secondary" loading={runningDue} onClick={handleRunDue}>
          运行到期任务
        </Button>
      </div>
      {dueError && <ErrorBar error={dueError} />}
      {dueResult && <JsonBlock value={dueResult} maxHeight="max-h-48" />}
      {loading && <LoadingBlock text="正在加载插件…" className="py-8" />}
      {error && <ErrorBar error={error} onRetry={reload} />}
      {!loading && !error && plugins.length === 0 && (
        <Card>
          <EmptyState title="暂无采集插件" className="py-10" />
        </Card>
      )}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
        {plugins.map((plugin) => (
          <PluginCard key={read(plugin, 'id')} plugin={plugin} onFlash={onFlash} />
        ))}
      </div>
    </section>
  )
}

/* ---------------- 候选审核 ---------------- */

function candidateSummary(candidate) {
  const listing = read(candidate, 'listing') ?? read(candidate, 'payload') ?? {}
  const parts = [
    read(listing, 'title'),
    read(listing, 'community'),
    read(listing, 'district'),
    read(listing, 'rentPrice') !== undefined ? `¥${read(listing, 'rentPrice')}/月` : null,
    read(listing, 'layout'),
  ].filter(Boolean)
  return parts.join(' · ') || '—'
}

function qualityText(candidate) {
  const quality = read(candidate, 'quality')
  if (!quality) return null
  const publishable = read(quality, 'publishable')
  const warnings = read(quality, 'warnings') ?? []
  const missing = read(quality, 'missingFields') ?? []
  if (publishable) {
    return warnings.length > 0 ? `可发布，提示: ${warnings.join('、')}` : '字段完整，可发布'
  }
  return `缺少 ${missing.join('、') || '必要字段'}`
}

function CandidateSection({ onFlash }) {
  const [status, setStatus] = useState('pending')
  const [page, setPage] = useState(1)
  const [selectedIds, setSelectedIds] = useState([])
  const [actionError, setActionError] = useState(null)
  const [busy, setBusy] = useState('')
  const [rejectTarget, setRejectTarget] = useState(null) // 'bulk' | candidate id
  const [rejectReason, setRejectReason] = useState('不符合发布要求')
  const [detail, setDetail] = useState(null)

  const { loading, error, data, reload } = useAsyncData(
    () => adminService.getCandidates({ status, page, limit: CANDIDATE_PAGE_SIZE }),
    [status, page],
  )

  const candidates = extractItems(data)
  const total = pick(data, 'total')
  const totalPages = pick(data, 'totalPages', 'total_pages')

  const allChecked = candidates.length > 0 && candidates.every((c) => selectedIds.includes(read(c, 'id')))

  const toggleAll = () => {
    setSelectedIds(allChecked ? [] : candidates.map((c) => read(c, 'id')))
  }
  const toggleOne = (id) => {
    setSelectedIds((prev) => (prev.includes(id) ? prev.filter((v) => v !== id) : [...prev, id]))
  }

  const runAction = async (key, action, successText) => {
    setBusy(key)
    setActionError(null)
    try {
      await action()
      onFlash(successText)
      setSelectedIds([])
      await reload()
    } catch (err) {
      setActionError(err)
    } finally {
      setBusy('')
    }
  }

  const handleApprove = (id) =>
    runAction(`approve-${id}`, () => adminService.approveCandidate(id), '候选已发布')

  const handleBulkApprove = () =>
    runAction('bulk-approve', () => adminService.bulkApproveCandidates({ ids: selectedIds }), `已批量通过 ${selectedIds.length} 条`)

  const confirmReject = () => {
    const reason = rejectReason.trim() || '后台人工驳回'
    if (rejectTarget === 'bulk') {
      runAction('bulk-reject', () => adminService.bulkRejectCandidates({ ids: selectedIds, reason, note: reason }), `已批量驳回 ${selectedIds.length} 条`)
    } else {
      runAction(`reject-${rejectTarget}`, () => adminService.rejectCandidate(rejectTarget, { reason, note: reason }), '候选已驳回')
    }
    setRejectTarget(null)
  }

  const statusBadge = (value) => {
    if (value === 'approved') return <Badge tone="success">已通过</Badge>
    if (value === 'rejected') return <Badge tone="danger">已驳回</Badge>
    return <Badge tone="warning">待审核</Badge>
  }

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <h2 className="text-base font-semibold text-ink-900">候选审核</h2>
        <div className="flex flex-wrap items-end gap-3">
          <StatusSelect
            label="状态"
            value={status}
            onChange={(event) => {
              setStatus(event.target.value)
              setPage(1)
              setSelectedIds([])
            }}
            className="w-36"
          >
            <option value="pending">待审核</option>
            <option value="approved">已通过</option>
            <option value="rejected">已驳回</option>
            <option value="all">全部</option>
          </StatusSelect>
          <Button
            size="sm"
            disabled={selectedIds.length === 0}
            loading={busy === 'bulk-approve'}
            onClick={handleBulkApprove}
          >
            批量通过（{selectedIds.length}）
          </Button>
          <Button
            size="sm"
            variant="danger"
            disabled={selectedIds.length === 0}
            loading={busy === 'bulk-reject'}
            onClick={() => setRejectTarget('bulk')}
          >
            批量驳回
          </Button>
        </div>
      </div>

      {error && <ErrorBar error={error} onRetry={reload} />}
      {actionError && <ErrorBar error={actionError} />}

      <div className="overflow-hidden rounded-2xl bg-white shadow-card ring-1 ring-ink-100/60">
        <div className="overflow-x-auto scrollbar-thin">
          <table className="min-w-full divide-y divide-ink-100 text-sm">
            <thead>
              <tr>
                <th className="w-10 px-4 py-3">
                  <input
                    type="checkbox"
                    aria-label="全选"
                    className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                    checked={allChecked}
                    onChange={toggleAll}
                  />
                </th>
                {['ID', '摘要', '来源', '质量', '状态', '更新时间'].map((label) => (
                  <th key={label} className="whitespace-nowrap px-4 py-3 text-left text-xs font-semibold uppercase tracking-wide text-ink-400">
                    {label}
                  </th>
                ))}
                <th className="px-4 py-3 text-right text-xs font-semibold uppercase tracking-wide text-ink-400">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-ink-100/80">
              {!loading &&
                candidates.map((candidate) => {
                  const id = read(candidate, 'id')
                  const cStatus = read(candidate, 'status', 'pending')
                  const publishable = read(read(candidate, 'quality') ?? {}, 'publishable', true)
                  return (
                    <tr key={id} className="transition-colors hover:bg-ink-50/60">
                      <td className="px-4 py-3">
                        <input
                          type="checkbox"
                          aria-label={`选择候选 ${id}`}
                          className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                          checked={selectedIds.includes(id)}
                          onChange={() => toggleOne(id)}
                        />
                      </td>
                      <td className="px-4 py-3 tabular-nums text-ink-400">{id}</td>
                      <td className="max-w-xs px-4 py-3">
                        <button
                          type="button"
                          className="block max-w-full truncate text-left text-ink-800 hover:text-brand-700"
                          onClick={() => setDetail(candidate)}
                          title="查看完整 payload"
                        >
                          {candidateSummary(candidate)}
                        </button>
                      </td>
                      <td className="px-4 py-3 text-ink-500">
                        {read(read(candidate, 'listing') ?? {}, 'provider') ?? read(candidate, 'source') ?? read(read(candidate, 'listing') ?? {}, 'source') ?? '—'}
                      </td>
                      <td className="max-w-[200px] px-4 py-3">
                        <span className={['block truncate text-xs', publishable ? 'text-emerald-600' : 'text-amber-600'].join(' ')}>
                          {qualityText(candidate) ?? '—'}
                        </span>
                      </td>
                      <td className="px-4 py-3">{statusBadge(cStatus)}</td>
                      <td className="whitespace-nowrap px-4 py-3 text-xs text-ink-400">
                        {formatTime(read(candidate, 'updatedAt') ?? read(candidate, 'createdAt'))}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-right">
                        <span className="space-x-3">
                          <button
                            type="button"
                            className="text-xs font-medium text-emerald-600 hover:text-emerald-700 disabled:cursor-not-allowed disabled:text-ink-300"
                            disabled={cStatus === 'approved' || busy === `approve-${id}`}
                            onClick={() => handleApprove(id)}
                          >
                            通过
                          </button>
                          <button
                            type="button"
                            className="text-xs font-medium text-rose-600 hover:text-rose-700 disabled:cursor-not-allowed disabled:text-ink-300"
                            disabled={cStatus === 'rejected' || busy === `reject-${id}`}
                            onClick={() => setRejectTarget(id)}
                          >
                            驳回
                          </button>
                        </span>
                      </td>
                    </tr>
                  )
                })}
            </tbody>
          </table>
        </div>
        {loading && <LoadingBlock className="py-10" />}
        {!loading && candidates.length === 0 && <EmptyState title="暂无候选" className="py-10" />}
        {!loading && candidates.length > 0 && (
          <Pagination
            page={page}
            totalPages={totalPages}
            total={total}
            hasMore={candidates.length >= CANDIDATE_PAGE_SIZE}
            onChange={setPage}
            className="border-t border-ink-100"
          />
        )}
      </div>

      {/* 驳回原因 */}
      <Modal
        open={rejectTarget !== null}
        onClose={() => setRejectTarget(null)}
        title={rejectTarget === 'bulk' ? `批量驳回 ${selectedIds.length} 条候选` : `驳回候选 #${rejectTarget}`}
        size="sm"
        footer={(
          <>
            <Button variant="secondary" size="sm" onClick={() => setRejectTarget(null)}>
              取消
            </Button>
            <Button variant="danger" size="sm" onClick={confirmReject}>
              确认驳回
            </Button>
          </>
        )}
      >
        <TextField
          label="驳回原因"
          value={rejectReason}
          onChange={(event) => setRejectReason(event.target.value)}
          hint="原因将记录在候选的审核备注中"
        />
      </Modal>

      {/* payload 详情 */}
      {detail !== null && (
        <Drawer
          open
          onClose={() => setDetail(null)}
          title={`候选 #${read(detail, 'id')}`}
          subtitle={candidateSummary(detail)}
        >
          <JsonBlock value={detail} maxHeight="max-h-[70vh]" />
        </Drawer>
      )}
    </section>
  )
}

/* ---------------- 手动导入 ---------------- */

function ImportCard({ onFlash }) {
  const [sourceName, setSourceName] = useState('manual_import')
  const [provider, setProvider] = useState('manual')
  const [city, setCity] = useState('上海')
  const [content, setContent] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  const handleImport = async () => {
    if (!content.trim()) {
      setError(new Error('请粘贴要导入的 JSON 数据'))
      return
    }
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      const response = await adminService.importListings({
        sourceName: sourceName.trim() || 'manual_import',
        provider: provider.trim() || 'manual',
        sourceType: 'manual_upload',
        city: city.trim() || '上海',
        content,
      })
      setResult(response)
      onFlash('导入任务已提交')
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader
        title="手动导入房源"
        description="粘贴 JSON 数组（每条含 external_id/title/district/price 等字段），导入后进入候选审核"
      />
      <CardBody className="space-y-3">
        {error && <ErrorBar error={error} />}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <TextField label="数据源名称" value={sourceName} onChange={(e) => setSourceName(e.target.value)} />
          <TextField label="Provider" value={provider} onChange={(e) => setProvider(e.target.value)} />
          <TextField label="城市" value={city} onChange={(e) => setCity(e.target.value)} />
        </div>
        <textarea
          className="h-48 w-full rounded-xl border-0 bg-ink-950 px-3.5 py-3 font-mono text-xs leading-5 text-ink-100 shadow-sm ring-1 ring-inset ring-ink-800 scrollbar-thin placeholder:text-ink-500 focus:ring-2 focus:ring-brand-500"
          placeholder='[{"external_id":"demo-1","title":"示例房源","district":"浦东","price":"4680元/月","layout":"1室1厅", ...}]'
          value={content}
          onChange={(event) => setContent(event.target.value)}
          aria-label="导入 JSON"
          spellCheck="false"
        />
        <Button size="sm" loading={busy} onClick={handleImport}>
          开始导入
        </Button>
        {result && <JsonBlock value={result} maxHeight="max-h-48" />}
      </CardBody>
    </Card>
  )
}

/**
 * 采集中心：概览统计、爬虫插件、候选审核与手动导入。
 */
function AdminIngestionPage() {
  const [flash, showFlash] = useFlash()

  return (
    <div className="space-y-8 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">采集中心</h1>
        <p className="mt-1 text-sm text-ink-400">爬虫插件运行、定时调度、候选审核与手动导入</p>
      </div>

      <SuccessBar message={flash} />

      <OverviewStats />
      <PluginSection onFlash={showFlash} />
      <CandidateSection onFlash={showFlash} />
      <ImportCard onFlash={showFlash} />
    </div>
  )
}

export default AdminIngestionPage

import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import Button from '../../components/ui/Button.jsx'
import { Card, CardBody, CardHeader } from '../../components/ui/Card.jsx'
import { SelectField, TextField } from '../../components/ui/Input.jsx'
import { EmptyState, LoadingBlock } from '../../components/ui/Feedback.jsx'
import { FilterInput } from '../../features/admin/components/FilterBar.jsx'
import JsonBlock from '../../features/admin/components/JsonBlock.jsx'
import { ErrorBar, SuccessBar } from '../../features/admin/components/Notice.jsx'
import SecretField from '../../features/admin/components/SecretField.jsx'
import StatCard from '../../features/admin/components/StatCard.jsx'
import { useAsyncData, useFlash } from '../../features/admin/hooks.js'
import { pick, read, toJson } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

/* ---------------- 状态卡 ---------------- */

function StatusSection() {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getNeo4jStatus(), [])

  if (loading) return <LoadingBlock text="正在检查 Neo4j 状态…" className="py-8" />
  if (error) return <ErrorBar error={error} onRetry={reload} />

  const configured = read(data, 'configured')
  const labels = read(data, 'labels') ?? []
  const relTypes = read(data, 'relationshipTypes') ?? []

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        {configured ? (
          read(data, 'ok') !== false ? <Badge tone="success">连接健康</Badge> : <Badge tone="danger">连接异常</Badge>
        ) : (
          <Badge tone="danger">未配置</Badge>
        )}
        {read(data, 'transport') && <Badge tone="brand">transport: {read(data, 'transport')}</Badge>}
        {read(data, 'summary') && <span className="text-xs text-ink-400">{read(data, 'summary')}</span>}
      </div>
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <StatCard label="Database" value={read(data, 'database') ?? '—'} />
        <StatCard label="节点数" value={read(data, 'nodeCount')?.toLocaleString?.() ?? read(data, 'nodeCount') ?? '—'} tone="brand" />
        <StatCard label="关系数" value={read(data, 'relationshipCount')?.toLocaleString?.() ?? read(data, 'relationshipCount') ?? '—'} />
        <StatCard label="API Key" value={read(data, 'apiKeyConfigured') ? '已配置' : '未配置'} tone={read(data, 'apiKeyConfigured') ? 'success' : 'warning'} />
      </div>
      {(labels.length > 0 || relTypes.length > 0) && (
        <div className="flex flex-wrap items-center gap-1.5 text-xs">
          {labels.map((label) => (
            <Badge key={`label-${label}`} tone="info">:{label}</Badge>
          ))}
          {relTypes.map((type) => (
            <Badge key={`rel-${type}`} tone="neutral">[{type}]</Badge>
          ))}
        </div>
      )}
    </div>
  )
}

/* ---------------- 配置表单 ---------------- */

function ConfigSection({ onFlash }) {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getNeo4jConfig(), [])
  const [draft, setDraft] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)

  const config = data ?? {}

  if (!loading && !error && data && draft === null) {
    setDraft({
      url: read(config, 'url', ''),
      username: read(config, 'username', 'neo4j'),
      database: read(config, 'database', 'neo4j'),
      transport: read(config, 'transport', 'auto'),
      timeoutSeconds: read(config, 'timeoutSeconds', 15),
      httpUrl: read(config, 'httpUrl', ''),
      proxyUrl: read(config, 'proxyUrl', ''),
      insecureSkipVerify: Boolean(read(config, 'insecureSkipVerify')),
      apiKey: '',
      clearApiKey: false,
    })
  }

  const setField = (key, value) => setDraft((prev) => ({ ...prev, [key]: value }))

  const handleSave = async () => {
    setSaving(true)
    setSaveError(null)
    try {
      await adminService.updateNeo4jConfig({
        ...draft,
        timeoutSeconds: Number(draft.timeoutSeconds) || 15,
      })
      onFlash('Neo4j 配置已保存')
      setField('apiKey', '')
      setField('clearApiKey', false)
      reload()
    } catch (err) {
      setSaveError(err)
    } finally {
      setSaving(false)
    }
  }

  const warnings = read(config, 'warnings') ?? []

  return (
    <Card>
      <CardHeader
        title="Neo4j 连接配置"
        description="图数据库连接参数，保存后即时生效"
        actions={<Button size="sm" loading={saving} onClick={handleSave} disabled={!draft}>保存配置</Button>}
      />
      <CardBody className="space-y-4">
        {loading && <LoadingBlock text="正在加载配置…" className="py-8" />}
        {error && <ErrorBar error={error} onRetry={reload} />}
        {saveError && <ErrorBar error={saveError} />}
        {warnings.length > 0 && (
          <div className="rounded-xl bg-amber-50 px-4 py-2.5 text-xs leading-5 text-amber-700 ring-1 ring-inset ring-amber-200">
            {warnings.join('；')}
          </div>
        )}

        {draft && (
          <>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <TextField label="Neo4j URI" placeholder="neo4j+s://xxx.databases.neo4j.io" value={draft.url} onChange={(e) => setField('url', e.target.value)} />
              <SecretField
                label="密码 / API Key"
                value={draft.apiKey}
                onChange={(v) => setField('apiKey', v)}
                configured={Boolean(read(config, 'apiKeyConfigured'))}
                clearChecked={draft.clearApiKey}
                onClearChange={(v) => setField('clearApiKey', v)}
              />
              <TextField label="用户名" value={draft.username} onChange={(e) => setField('username', e.target.value)} />
              <TextField label="Database" value={draft.database} onChange={(e) => setField('database', e.target.value)} />
              <SelectField label="Transport" value={draft.transport} onChange={(e) => setField('transport', e.target.value)}>
                <option value="auto">auto（自动）</option>
                <option value="bolt">bolt</option>
                <option value="http">http</option>
              </SelectField>
              <TextField label="超时（秒）" type="number" min="1" max="120" value={draft.timeoutSeconds} onChange={(e) => setField('timeoutSeconds', e.target.value)} />
            </div>

            <details className="rounded-xl bg-ink-50 p-4">
              <summary className="cursor-pointer text-sm font-medium text-ink-700">HTTP / 代理 高级选项</summary>
              <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
                <TextField label="HTTP URL（可选）" value={draft.httpUrl} onChange={(e) => setField('httpUrl', e.target.value)} />
                <TextField label="代理 URL（可选）" value={draft.proxyUrl} onChange={(e) => setField('proxyUrl', e.target.value)} />
                <label className="flex items-center gap-2 text-sm text-ink-700 sm:col-span-2">
                  <input
                    type="checkbox"
                    className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                    checked={draft.insecureSkipVerify}
                    onChange={(e) => setField('insecureSkipVerify', e.target.checked)}
                  />
                  跳过 TLS 证书校验（仅调试环境使用）
                </label>
              </div>
            </details>
          </>
        )}
      </CardBody>
    </Card>
  )
}

/* ---------------- 同步房源 ---------------- */

function SyncSection({ onFlash }) {
  const [city, setCity] = useState('上海')
  const [limit, setLimit] = useState(200)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  const handleSync = async () => {
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      const response = await adminService.syncListingsToNeo4j({ city: city.trim(), limit: Number(limit) || 200 })
      setResult(response)
      onFlash('图谱同步已完成')
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader title="同步房源到图谱" description="将已发布房源写入 Neo4j（Listing/City/BusinessArea 节点与关系）" />
      <CardBody className="space-y-3">
        {error && <ErrorBar error={error} />}
        <div className="flex flex-wrap items-end gap-3">
          <FilterInput label="城市" value={city} onChange={(e) => setCity(e.target.value)} className="w-36" />
          <FilterInput label="数量上限" type="number" min="1" max="1000" value={limit} onChange={(e) => setLimit(e.target.value)} className="w-32" />
          <Button size="sm" loading={busy} onClick={handleSync}>
            开始同步
          </Button>
        </div>
        {result && (
          <div className="space-y-2">
            <p className="text-sm text-emerald-700">
              {read(result, 'summary') ?? `已同步 ${read(result, 'synced') ?? 0} 条房源`}
            </p>
            <JsonBlock value={result} maxHeight="max-h-48" />
          </div>
        )}
      </CardBody>
    </Card>
  )
}

/* ---------------- Cypher 控制台 ---------------- */

const DEFAULT_CYPHER = 'MATCH (l:Listing)-[:IN_CITY]->(c:City) RETURN l.title AS title, c.name AS city LIMIT 20'

function CypherSection() {
  const [query, setQuery] = useState(DEFAULT_CYPHER)
  const [limit, setLimit] = useState(20)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  const handleRun = async () => {
    if (!query.trim()) return
    setBusy(true)
    setError(null)
    try {
      const response = await adminService.queryNeo4j({ query: query.trim(), limit: Number(limit) || 20 })
      if (response?.ok === false) {
        setError(new Error(response?.summary || '查询失败'))
        setResult(null)
      } else {
        setResult(response)
      }
    } catch (err) {
      setError(err)
      setResult(null)
    } finally {
      setBusy(false)
    }
  }

  const rows = pick(result, 'rows') ?? []
  // 若所有行都是相同 key 的扁平对象，则渲染为表格，否则渲染 JSON
  const columns = rows.length > 0 ? Object.keys(rows[0] ?? {}) : []
  const uniform =
    rows.length > 0 &&
    rows.every(
      (row) =>
        row &&
        typeof row === 'object' &&
        !Array.isArray(row) &&
        Object.keys(row).length === columns.length &&
        columns.every((key) => key in row),
    )

  return (
    <Card>
      <CardHeader title="Cypher 控制台" description="只读查询（仅支持 MATCH/RETURN 类语句）" />
      <CardBody className="space-y-3">
        <textarea
          className="h-28 w-full rounded-xl border-0 bg-ink-950 px-3.5 py-3 font-mono text-xs leading-5 text-ink-100 shadow-sm ring-1 ring-inset ring-ink-800 scrollbar-thin focus:ring-2 focus:ring-brand-500"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          aria-label="Cypher 查询"
          spellCheck="false"
        />
        <div className="flex flex-wrap items-end gap-3">
          <FilterInput label="行数上限" type="number" min="1" max="200" value={limit} onChange={(e) => setLimit(e.target.value)} className="w-28" />
          <Button size="sm" loading={busy} onClick={handleRun}>
            执行查询
          </Button>
          {result && <span className="text-xs text-ink-400">{rows.length} 行</span>}
        </div>

        {error && <ErrorBar error={error} />}
        {result && rows.length === 0 && <EmptyState title="查询无结果" className="py-8" />}

        {rows.length > 0 && uniform ? (
          <div className="overflow-x-auto rounded-xl ring-1 ring-ink-100 scrollbar-thin">
            <table className="min-w-full divide-y divide-ink-100 text-sm">
              <thead>
                <tr>
                  {columns.map((column) => (
                    <th key={column} className="whitespace-nowrap px-4 py-2.5 text-left text-xs font-semibold uppercase tracking-wide text-ink-400">
                      {column}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-ink-100/80">
                {rows.map((row, index) => (
                  <tr key={index} className="hover:bg-ink-50/60">
                    {columns.map((column) => {
                      const value = row[column]
                      return (
                        <td key={column} className="max-w-xs truncate px-4 py-2.5 text-ink-700" title={typeof value === 'object' ? toJson(value) : String(value ?? '')}>
                          {value === null || value === undefined
                            ? '—'
                            : typeof value === 'object'
                              ? toJson(value)
                              : String(value)}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          rows.length > 0 && <JsonBlock value={rows} />
        )}
      </CardBody>
    </Card>
  )
}

/**
 * 图谱 Neo4j：状态、连接配置、同步与 Cypher 控制台。
 */
function AdminGraphStorePage() {
  const [flash, showFlash] = useFlash()

  return (
    <div className="space-y-6 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">图谱 Neo4j</h1>
        <p className="mt-1 text-sm text-ink-400">图数据库状态、连接配置与只读查询</p>
      </div>

      <SuccessBar message={flash} />

      <StatusSection />
      <ConfigSection onFlash={showFlash} />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <SyncSection onFlash={showFlash} />
        <CypherSection />
      </div>
    </div>
  )
}

export default AdminGraphStorePage

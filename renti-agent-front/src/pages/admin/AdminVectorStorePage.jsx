import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import Button from '../../components/ui/Button.jsx'
import { Card, CardBody, CardHeader } from '../../components/ui/Card.jsx'
import { SelectField, TextField } from '../../components/ui/Input.jsx'
import { EmptyState, LoadingBlock } from '../../components/ui/Feedback.jsx'
import { FilterInput, FilterSelect } from '../../features/admin/components/FilterBar.jsx'
import JsonBlock from '../../features/admin/components/JsonBlock.jsx'
import { ErrorBar, SuccessBar } from '../../features/admin/components/Notice.jsx'
import SecretField from '../../features/admin/components/SecretField.jsx'
import StatCard from '../../features/admin/components/StatCard.jsx'
import { useAsyncData, useFlash } from '../../features/admin/hooks.js'
import { pick, read } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

/* ---------------- 状态卡 ---------------- */

function StatusSection() {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getQdrantStatus(), [])

  if (loading) return <LoadingBlock text="正在检查 Qdrant 状态…" className="py-8" />
  if (error) return <ErrorBar error={error} onRetry={reload} />

  const configured = read(data, 'configured') ?? read(data, 'qdrantConfigured')
  const collectionExists = read(data, 'collectionExists')
  const healthy = configured && collectionExists !== false

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        {healthy ? <Badge tone="success">连接健康</Badge> : configured ? <Badge tone="warning">缺少 Collection</Badge> : <Badge tone="danger">未配置</Badge>}
        {read(data, 'effectiveEmbeddingProvider') && (
          <Badge tone="brand">嵌入：{read(data, 'effectiveEmbeddingProvider')}</Badge>
        )}
        {read(data, 'summary') && <span className="text-xs text-ink-400">{read(data, 'summary')}</span>}
      </div>
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <StatCard label="Collection" value={read(data, 'collection') ?? '—'} />
        <StatCard label="向量点数" value={read(data, 'pointsCount')?.toLocaleString?.() ?? read(data, 'pointsCount') ?? '—'} tone="brand" />
        <StatCard label="已索引向量" value={read(data, 'indexedVectorsCount') ?? '—'} />
        <StatCard
          label="向量维度 / 距离"
          value={read(data, 'vectorSize') ? `${read(data, 'vectorSize')} · ${read(data, 'distance') ?? '—'}` : '—'}
        />
      </div>
    </div>
  )
}

/* ---------------- RAG 配置表单 ---------------- */

const SECRET_FIELDS = [
  { key: 'qdrantApiKey', clearKey: 'clearQdrantApiKey', configuredKey: 'qdrantConfigured', label: 'Qdrant API Key' },
  { key: 'jinaApiKey', clearKey: 'clearJinaApiKey', configuredKey: 'jinaApiKeyConfigured', label: 'Jina API Key' },
  { key: 'embeddingApiKey', clearKey: 'clearEmbeddingApiKey', configuredKey: 'embeddingApiKeyConfigured', label: '通用嵌入 API Key' },
  { key: 'deepseekApiKey', clearKey: 'clearDeepseekApiKey', configuredKey: 'deepseekApiKeyConfigured', label: 'DeepSeek API Key' },
]

function RagConfigSection({ onFlash }) {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getRagConfig(), [])
  const [draft, setDraft] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)

  const config = data ?? {}

  // 首次数据到达后初始化 draft
  if (!loading && !error && data && draft === null) {
    setDraft({
      embeddingProvider: read(config, 'embeddingProvider', 'auto'),
      qdrantUrl: read(config, 'qdrantUrl', ''),
      qdrantCollection: read(config, 'qdrantCollection', 'renti_listings_v2'),
      proxyUrl: read(config, 'proxyUrl', ''),
      jinaUrl: read(config, 'jinaUrl', 'https://api.jina.ai/v1'),
      jinaModel: read(config, 'jinaModel', 'jina-embeddings-v3'),
      embeddingBaseUrl: read(config, 'embeddingBaseUrl', ''),
      embeddingModel: read(config, 'embeddingModel', ''),
      deepseekBaseUrl: read(config, 'deepseekBaseUrl', ''),
      deepseekEmbeddingModel: read(config, 'deepseekEmbeddingModel', ''),
      localEmbeddingDimensions: read(config, 'localEmbeddingDimensions', 384),
      mqeEnabled: Boolean(read(config, 'mqeEnabled')),
      mqeQueryCount: read(config, 'mqeQueryCount', 3),
      hydeEnabled: Boolean(read(config, 'hydeEnabled')),
      candidatePoolMultiplier: read(config, 'candidatePoolMultiplier', 4),
      queryExpansionProvider: read(config, 'queryExpansionProvider', 'local'),
      llmRerankEnabled: Boolean(read(config, 'llmRerankEnabled')),
      llmRerankTopN: read(config, 'llmRerankTopN', 12),
      qdrantApiKey: '',
      clearQdrantApiKey: false,
      jinaApiKey: '',
      clearJinaApiKey: false,
      embeddingApiKey: '',
      clearEmbeddingApiKey: false,
      deepseekApiKey: '',
      clearDeepseekApiKey: false,
    })
  }

  const setField = (key, value) => setDraft((prev) => ({ ...prev, [key]: value }))

  const handleSave = async () => {
    setSaving(true)
    setSaveError(null)
    try {
      await adminService.updateRagConfig({
        ...draft,
        localEmbeddingDimensions: Number(draft.localEmbeddingDimensions) || 384,
        mqeQueryCount: Number(draft.mqeQueryCount) || 3,
        candidatePoolMultiplier: Number(draft.candidatePoolMultiplier) || 4,
        llmRerankTopN: Number(draft.llmRerankTopN) || 12,
      })
      onFlash('RAG 配置已保存')
      SECRET_FIELDS.forEach(({ key, clearKey }) => {
        setField(key, '')
        setField(clearKey, false)
      })
      reload()
    } catch (err) {
      setSaveError(err)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card>
      <CardHeader
        title="RAG 配置"
        description={`嵌入策略与 Qdrant 连接 · 当前生效：${read(config, 'effectiveEmbeddingProvider') ?? '—'}`}
        actions={<Button size="sm" loading={saving} onClick={handleSave} disabled={!draft}>保存配置</Button>}
      />
      <CardBody className="space-y-5">
        {loading && <LoadingBlock text="正在加载 RAG 配置…" className="py-8" />}
        {error && <ErrorBar error={error} onRetry={reload} />}
        {saveError && <ErrorBar error={saveError} />}

        {draft && (
          <>
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <TextField label="Qdrant URL" value={draft.qdrantUrl} onChange={(e) => setField('qdrantUrl', e.target.value)} />
              <TextField label="Collection" value={draft.qdrantCollection} onChange={(e) => setField('qdrantCollection', e.target.value)} />
              <SecretField
                label="Qdrant API Key"
                value={draft.qdrantApiKey}
                onChange={(v) => setField('qdrantApiKey', v)}
                configured={Boolean(read(config, 'qdrantConfigured'))}
                clearChecked={draft.clearQdrantApiKey}
                onClearChange={(v) => setField('clearQdrantApiKey', v)}
              />
              <TextField label="代理 URL（可选）" value={draft.proxyUrl} onChange={(e) => setField('proxyUrl', e.target.value)} />
            </div>

            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              <SelectField label="嵌入 Provider" value={draft.embeddingProvider} onChange={(e) => setField('embeddingProvider', e.target.value)}>
                <option value="auto">auto（自动选择）</option>
                <option value="jina">jina</option>
                <option value="openai">openai 兼容</option>
                <option value="deepseek">deepseek</option>
                <option value="local_hash">local_hash（本地）</option>
              </SelectField>
              <TextField
                label="本地嵌入维度"
                type="number"
                min="32"
                max="4096"
                value={draft.localEmbeddingDimensions}
                onChange={(e) => setField('localEmbeddingDimensions', e.target.value)}
              />
              <TextField label="Jina URL" value={draft.jinaUrl} onChange={(e) => setField('jinaUrl', e.target.value)} />
              <TextField label="Jina 模型" value={draft.jinaModel} onChange={(e) => setField('jinaModel', e.target.value)} />
              <SecretField
                label="Jina API Key"
                value={draft.jinaApiKey}
                onChange={(v) => setField('jinaApiKey', v)}
                configured={Boolean(read(config, 'jinaApiKeyConfigured'))}
                clearChecked={draft.clearJinaApiKey}
                onClearChange={(v) => setField('clearJinaApiKey', v)}
              />
            </div>

            <details className="rounded-xl bg-ink-50 p-4">
              <summary className="cursor-pointer text-sm font-medium text-ink-700">通用 / DeepSeek 嵌入（可选）</summary>
              <div className="mt-3 grid grid-cols-1 gap-4 sm:grid-cols-2">
                <TextField label="通用嵌入 Base URL" value={draft.embeddingBaseUrl} onChange={(e) => setField('embeddingBaseUrl', e.target.value)} />
                <TextField label="通用嵌入模型" value={draft.embeddingModel} onChange={(e) => setField('embeddingModel', e.target.value)} />
                <SecretField
                  label="通用嵌入 API Key"
                  value={draft.embeddingApiKey}
                  onChange={(v) => setField('embeddingApiKey', v)}
                  configured={Boolean(read(config, 'embeddingApiKeyConfigured'))}
                  clearChecked={draft.clearEmbeddingApiKey}
                  onClearChange={(v) => setField('clearEmbeddingApiKey', v)}
                />
                <TextField label="DeepSeek Base URL" value={draft.deepseekBaseUrl} onChange={(e) => setField('deepseekBaseUrl', e.target.value)} />
                <TextField label="DeepSeek 嵌入模型" value={draft.deepseekEmbeddingModel} onChange={(e) => setField('deepseekEmbeddingModel', e.target.value)} />
                <SecretField
                  label="DeepSeek API Key"
                  value={draft.deepseekApiKey}
                  onChange={(v) => setField('deepseekApiKey', v)}
                  configured={Boolean(read(config, 'deepseekApiKeyConfigured'))}
                  clearChecked={draft.clearDeepseekApiKey}
                  onClearChange={(v) => setField('clearDeepseekApiKey', v)}
                />
              </div>
            </details>

            <div className="rounded-xl bg-ink-50 p-4">
              <p className="mb-3 text-sm font-medium text-ink-700">扩展检索策略</p>
              <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
                <label className="flex items-center gap-2 text-sm text-ink-700">
                  <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={draft.mqeEnabled} onChange={(e) => setField('mqeEnabled', e.target.checked)} />
                  MQE 多查询扩展
                </label>
                <label className="block w-32">
                  <span className="mb-1 block text-xs font-medium text-ink-500">MQE 查询数</span>
                  <input type="number" min="1" max="8" className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={draft.mqeQueryCount} onChange={(e) => setField('mqeQueryCount', e.target.value)} />
                </label>
                <label className="flex items-center gap-2 text-sm text-ink-700">
                  <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={draft.hydeEnabled} onChange={(e) => setField('hydeEnabled', e.target.checked)} />
                  HyDE 假设文档
                </label>
                <label className="block w-32">
                  <span className="mb-1 block text-xs font-medium text-ink-500">候选池倍率</span>
                  <input type="number" min="1" max="10" className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={draft.candidatePoolMultiplier} onChange={(e) => setField('candidatePoolMultiplier', e.target.value)} />
                </label>
                <label className="block w-40">
                  <span className="mb-1 block text-xs font-medium text-ink-500">扩展生成方式</span>
                  <select className="h-9 w-full rounded-xl border-0 bg-white px-2.5 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={draft.queryExpansionProvider} onChange={(e) => setField('queryExpansionProvider', e.target.value)}>
                    <option value="local">local（本地规则）</option>
                    <option value="llm">llm（大模型生成）</option>
                  </select>
                </label>
                <label className="flex items-center gap-2 text-sm text-ink-700">
                  <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={draft.llmRerankEnabled} onChange={(e) => setField('llmRerankEnabled', e.target.checked)} />
                  LLM 复排
                </label>
                <label className="block w-32">
                  <span className="mb-1 block text-xs font-medium text-ink-500">复排 TopN</span>
                  <input type="number" min="3" max="30" className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={draft.llmRerankTopN} onChange={(e) => setField('llmRerankTopN', e.target.value)} />
                </label>
              </div>
            </div>
          </>
        )}
      </CardBody>
    </Card>
  )
}

/* ---------------- 重建索引 ---------------- */

function IndexSection({ onFlash }) {
  const [city, setCity] = useState('上海')
  const [limit, setLimit] = useState(200)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  const handleRun = async () => {
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      const response = await adminService.indexListings({ city: city.trim(), limit: Number(limit) || 200 })
      setResult(response)
      onFlash('索引任务已完成')
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader title="重建索引" description="将已发布房源写入 Qdrant 向量库" />
      <CardBody className="space-y-3">
        {error && <ErrorBar error={error} />}
        <div className="flex flex-wrap items-end gap-3">
          <FilterInput label="城市" value={city} onChange={(e) => setCity(e.target.value)} className="w-36" />
          <FilterInput label="数量上限" type="number" min="1" max="1000" value={limit} onChange={(e) => setLimit(e.target.value)} className="w-32" />
          <Button size="sm" loading={busy} onClick={handleRun}>
            开始索引
          </Button>
        </div>
        {result && (
          <div className="space-y-2">
            <p className="text-sm text-emerald-700">
              {read(result, 'summary') ?? `已索引 ${read(result, 'indexed') ?? 0} 条房源`}
            </p>
            <JsonBlock value={result} maxHeight="max-h-48" />
          </div>
        )}
      </CardBody>
    </Card>
  )
}

/* ---------------- 语义搜索测试 ---------------- */

function SearchSection() {
  const [text, setText] = useState('两千五以内 一室户 靠近地铁')
  const [city, setCity] = useState('上海')
  const [limit, setLimit] = useState(8)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  const handleSearch = async () => {
    if (!text.trim()) return
    setBusy(true)
    setError(null)
    try {
      const response = await adminService.searchQdrant({ text: text.trim(), city: city.trim(), limit: Number(limit) || 8 })
      setResult(response)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  const matches = pick(result, 'matches') ?? []
  const queries = pick(result, 'searchQueries') ?? []

  return (
    <Card>
      <CardHeader title="语义搜索测试" description="验证向量召回质量与查询扩展计划" />
      <CardBody className="space-y-3">
        {error && <ErrorBar error={error} />}
        <div className="flex flex-wrap items-end gap-3">
          <FilterInput label="查询文本" value={text} onChange={(e) => setText(e.target.value)} className="w-72" />
          <FilterInput label="城市" value={city} onChange={(e) => setCity(e.target.value)} className="w-28" />
          <FilterInput label="返回数" type="number" min="1" max="50" value={limit} onChange={(e) => setLimit(e.target.value)} className="w-24" />
          <Button size="sm" loading={busy} onClick={handleSearch}>
            搜索
          </Button>
        </div>

        {result && queries.length > 0 && (
          <div className="flex flex-wrap gap-1.5">
            {queries.map((q, index) => (
              <Badge key={index} tone="info">
                {read(q, 'label') ?? read(q, 'strategy') ?? `Q${index + 1}`}
              </Badge>
            ))}
          </div>
        )}

        {result && matches.length === 0 && <EmptyState title="无命中结果" className="py-8" />}
        {matches.length > 0 && (
          <ul className="space-y-2">
            {matches.map((match, index) => (
              <li key={read(match, 'listingId') ?? index} className="rounded-xl bg-ink-50 px-4 py-3">
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="tabular-nums text-sm font-semibold text-brand-600">
                    {typeof read(match, 'score') === 'number' ? read(match, 'score').toFixed(4) : read(match, 'score')}
                  </span>
                  <span className="font-medium text-ink-900">{read(match, 'title') ?? read(match, 'community') ?? '—'}</span>
                  <span className="text-xs text-ink-500">
                    {[read(match, 'district'), read(match, 'businessArea'), read(match, 'rentPrice') !== undefined ? `¥${read(match, 'rentPrice')}/月` : null, read(match, 'layout')]
                      .filter(Boolean)
                      .join(' · ')}
                  </span>
                </div>
                {(read(match, 'tags') ?? []).length > 0 && (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {(read(match, 'tags') ?? []).slice(0, 6).map((tag) => (
                      <Badge key={tag} tone="neutral">{tag}</Badge>
                    ))}
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardBody>
    </Card>
  )
}

/* ---------------- 向量点浏览（游标分页） ---------------- */

function PointsSection() {
  const [city, setCity] = useState('上海')
  const [status, setStatus] = useState('active')
  const [limit, setLimit] = useState(10)
  const [offset, setOffset] = useState(null)
  const [offsetStack, setOffsetStack] = useState([]) // 历史 offset，用于上一页
  const [expandedId, setExpandedId] = useState(null)

  const { loading, error, data, reload } = useAsyncData(
    () =>
      adminService.getQdrantPoints({
        city: city || undefined,
        status: status === 'all' ? undefined : status,
        limit,
        offset: offset ?? undefined,
      }),
    [city, status, limit, offset],
  )

  const points = pick(data, 'points') ?? []
  const nextPageOffset = pick(data, 'nextPageOffset', 'next_page_offset')

  const resetPaging = () => {
    setOffset(null)
    setOffsetStack([])
  }

  return (
    <Card>
      <CardHeader title="向量点浏览" description="按城市/状态浏览 Qdrant 中已写入的向量点" />
      <CardBody className="space-y-3">
        <div className="flex flex-wrap items-end gap-3">
          <FilterInput
            label="城市"
            value={city}
            onChange={(e) => {
              setCity(e.target.value)
              resetPaging()
            }}
            className="w-28"
          />
          <FilterSelect
            label="状态"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value)
              resetPaging()
            }}
            className="w-32"
          >
            <option value="active">在架</option>
            <option value="unavailable">已下架</option>
            <option value="all">全部</option>
          </FilterSelect>
          <FilterSelect
            label="每页"
            value={limit}
            onChange={(e) => {
              setLimit(Number(e.target.value))
              resetPaging()
            }}
            className="w-24"
          >
            <option value={5}>5</option>
            <option value={10}>10</option>
            <option value={20}>20</option>
          </FilterSelect>
        </div>

        {error && <ErrorBar error={error} onRetry={reload} />}
        {loading && <LoadingBlock text="正在加载向量点…" className="py-8" />}
        {!loading && !error && points.length === 0 && <EmptyState title="暂无向量点" className="py-8" />}

        {!loading && points.length > 0 && (
          <ul className="space-y-2">
            {points.map((point, index) => {
              const id = read(point, 'pointId') ?? index
              return (
                <li key={id} className="rounded-xl ring-1 ring-ink-100">
                  <button
                    type="button"
                    className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-left hover:bg-ink-50"
                    onClick={() => setExpandedId(expandedId === id ? null : id)}
                  >
                    <span className="font-medium text-ink-900">{read(point, 'title') ?? read(point, 'community') ?? '—'}</span>
                    <span className="text-xs text-ink-500">
                      {[read(point, 'district'), read(point, 'businessArea'), read(point, 'rentPrice') !== undefined ? `¥${read(point, 'rentPrice')}/月` : null, read(point, 'layout')]
                        .filter(Boolean)
                        .join(' · ')}
                    </span>
                    <Badge tone={read(point, 'status') === 'active' ? 'success' : 'neutral'}>{read(point, 'status') ?? '—'}</Badge>
                    <span className="ml-auto truncate text-xs text-ink-300">{read(point, 'listingId')}</span>
                  </button>
                  {expandedId === id && (
                    <div className="border-t border-ink-100 p-3">
                      <JsonBlock value={point} maxHeight="max-h-72" />
                    </div>
                  )}
                </li>
              )
            })}
          </ul>
        )}

        <div className="flex items-center justify-between text-xs text-ink-400">
          <span>第 {offsetStack.length + 1} 页</span>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="secondary"
              disabled={offsetStack.length === 0 || loading}
              onClick={() => {
                const stack = [...offsetStack]
                const prev = stack.pop()
                setOffsetStack(stack)
                setOffset(prev ?? null)
              }}
            >
              上一页
            </Button>
            <Button
              size="sm"
              variant="secondary"
              disabled={nextPageOffset === undefined || nextPageOffset === null || loading}
              onClick={() => {
                setOffsetStack((stack) => [...stack, offset])
                setOffset(nextPageOffset)
              }}
            >
              下一页
            </Button>
          </div>
        </div>
      </CardBody>
    </Card>
  )
}

/**
 * 向量库 Qdrant：状态、RAG 配置、索引、点浏览与语义搜索。
 */
function AdminVectorStorePage() {
  const [flash, showFlash] = useFlash()

  return (
    <div className="space-y-6 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">向量库 Qdrant</h1>
        <p className="mt-1 text-sm text-ink-400">连接状态、嵌入策略配置与召回验证</p>
      </div>

      <SuccessBar message={flash} />

      <StatusSection />
      <RagConfigSection onFlash={showFlash} />
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-2">
        <IndexSection onFlash={showFlash} />
        <SearchSection />
      </div>
      <PointsSection />
    </div>
  )
}

export default AdminVectorStorePage

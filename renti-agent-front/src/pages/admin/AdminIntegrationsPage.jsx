import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import Button from '../../components/ui/Button.jsx'
import { Card, CardBody, CardHeader } from '../../components/ui/Card.jsx'
import { SelectField, TextField } from '../../components/ui/Input.jsx'
import { LoadingBlock } from '../../components/ui/Feedback.jsx'
import { ErrorBar, SuccessBar } from '../../features/admin/components/Notice.jsx'
import SecretField from '../../features/admin/components/SecretField.jsx'
import { useAsyncData, useFlash } from '../../features/admin/hooks.js'
import { pick, read } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

/** 折叠分区容器 */
function Section({ title, description, defaultOpen = true, children }) {
  return (
    <details className="group rounded-2xl bg-white shadow-card ring-1 ring-ink-100/60" open={defaultOpen}>
      <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4">
        <div>
          <h3 className="text-sm font-semibold text-ink-900">{title}</h3>
          {description && <p className="mt-0.5 text-xs text-ink-500">{description}</p>}
        </div>
        <svg
          className="h-4 w-4 shrink-0 text-ink-400 transition-transform group-open:rotate-180"
          viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
      </summary>
      <div className="border-t border-ink-100 px-5 py-4">{children}</div>
    </details>
  )
}

/* ---------------- 系统集成配置（LLM / RAG / Neo4j） ---------------- */

function IntegrationsSection() {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getIntegrationsConfig(), [])
  const [draft, setDraft] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const [flash, showFlash] = useFlash(2000)

  const llm = pick(data, 'llm') ?? {}
  const rag = pick(data, 'rag') ?? {}
  const neo4j = pick(data, 'neo4j') ?? {}
  // 旧版响应的 LLM 字段带 deepseek 前缀，新版为 baseUrl/chatModel/...，按响应实际形态选择提交字段名
  const legacyLlm = 'deepseekBaseUrl' in llm || 'deepseek_base_url' in llm

  if (!loading && !error && data && draft === null) {
    setDraft({
      llm: {
        baseUrl: read(llm, 'baseUrl') ?? read(llm, 'deepseekBaseUrl') ?? '',
        chatModel: read(llm, 'chatModel') ?? read(llm, 'deepseekChatModel') ?? 'deepseek-chat',
        timeoutSeconds: read(llm, 'timeoutSeconds') ?? read(llm, 'deepseekTimeoutSeconds') ?? 60,
        apiKey: '',
        clearApiKey: false,
      },
      rag: {
        qdrantUrl: read(rag, 'qdrantUrl', ''),
        qdrantCollection: read(rag, 'qdrantCollection', ''),
        jinaUrl: read(rag, 'jinaUrl', ''),
        jinaModel: read(rag, 'jinaModel', ''),
        embeddingProvider: read(rag, 'embeddingProvider', 'auto'),
        localEmbeddingDimensions: read(rag, 'localEmbeddingDimensions', 384),
        mqeEnabled: Boolean(read(rag, 'mqeEnabled')),
        hydeEnabled: Boolean(read(rag, 'hydeEnabled')),
        llmRerankEnabled: Boolean(read(rag, 'llmRerankEnabled')),
        llmRerankTopN: read(rag, 'llmRerankTopN', 12),
        queryExpansionProvider: read(rag, 'queryExpansionProvider', 'local'),
        proxyUrl: read(rag, 'proxyUrl', ''),
        timeoutSeconds: read(rag, 'timeoutSeconds', 30),
        qdrantApiKey: '',
        clearQdrantApiKey: false,
        jinaApiKey: '',
        clearJinaApiKey: false,
      },
      neo4j: {
        url: read(neo4j, 'url', ''),
        username: read(neo4j, 'username', 'neo4j'),
        database: read(neo4j, 'database', 'neo4j'),
        transport: read(neo4j, 'transport', 'auto'),
        proxyUrl: read(neo4j, 'proxyUrl', ''),
        insecureSkipVerify: Boolean(read(neo4j, 'insecureSkipVerify')),
        timeoutSeconds: read(neo4j, 'timeoutSeconds', 15),
        apiKey: '',
        clearApiKey: false,
      },
    })
  }

  const setField = (section, key, value) =>
    setDraft((prev) => ({ ...prev, [section]: { ...prev[section], [key]: value } }))

  const handleSave = async () => {
    setSaving(true)
    setSaveError(null)
    const llmPayload = legacyLlm
      ? {
          deepseekBaseUrl: draft.llm.baseUrl,
          deepseekChatModel: draft.llm.chatModel,
          deepseekTimeoutSeconds: Number(draft.llm.timeoutSeconds) || 60,
          deepseekApiKey: draft.llm.apiKey,
          clearDeepseekApiKey: draft.llm.clearApiKey,
        }
      : {
          baseUrl: draft.llm.baseUrl,
          chatModel: draft.llm.chatModel,
          timeoutSeconds: Number(draft.llm.timeoutSeconds) || 60,
          apiKey: draft.llm.apiKey,
          clearApiKey: draft.llm.clearApiKey,
        }
    try {
      await adminService.updateIntegrationsConfig({
        llm: llmPayload,
        rag: {
          ...draft.rag,
          localEmbeddingDimensions: Number(draft.rag.localEmbeddingDimensions) || 384,
          llmRerankTopN: Number(draft.rag.llmRerankTopN) || 12,
          timeoutSeconds: Number(draft.rag.timeoutSeconds) || 30,
        },
        neo4j: {
          ...draft.neo4j,
          timeoutSeconds: Number(draft.neo4j.timeoutSeconds) || 15,
        },
      })
      showFlash('配置已保存，已即时生效')
      setDraft((prev) => ({
        ...prev,
        llm: { ...prev.llm, apiKey: '', clearApiKey: false },
        rag: { ...prev.rag, qdrantApiKey: '', clearQdrantApiKey: false, jinaApiKey: '', clearJinaApiKey: false },
        neo4j: { ...prev.neo4j, apiKey: '', clearApiKey: false },
      }))
      reload()
    } catch (err) {
      setSaveError(err)
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <LoadingBlock text="正在加载系统集成配置…" className="py-10" />
  if (error) return <ErrorBar error={error} onRetry={reload} />
  if (!draft) return null

  const llmConfigured = Boolean(read(llm, 'apiKeyConfigured') ?? read(llm, 'deepseekApiKeyConfigured'))

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-base font-semibold text-ink-900">系统集成</h2>
        <Button size="sm" loading={saving} onClick={handleSave}>
          保存全部配置
        </Button>
      </div>
      <SuccessBar message={flash} />
      {saveError && <ErrorBar error={saveError} />}

      <Section title="LLM（DeepSeek）" description="对话模型接入参数">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField label="Base URL" value={draft.llm.baseUrl} onChange={(e) => setField('llm', 'baseUrl', e.target.value)} />
          <SelectField label="Chat Model" value={draft.llm.chatModel} onChange={(e) => setField('llm', 'chatModel', e.target.value)}>
            <option value="deepseek-chat">deepseek-chat</option>
            <option value="deepseek-reasoner">deepseek-reasoner</option>
          </SelectField>
          <SecretField
            label="API Key"
            value={draft.llm.apiKey}
            onChange={(v) => setField('llm', 'apiKey', v)}
            configured={llmConfigured}
            clearChecked={draft.llm.clearApiKey}
            onClearChange={(v) => setField('llm', 'clearApiKey', v)}
          />
          <TextField label="超时（秒）" type="number" min="1" max="120" value={draft.llm.timeoutSeconds} onChange={(e) => setField('llm', 'timeoutSeconds', e.target.value)} />
        </div>
      </Section>

      <Section title="RAG（Jina / Qdrant）" description="嵌入与向量检索参数（更细粒度配置见「向量库 Qdrant」页）" defaultOpen={false}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField label="Qdrant URL" value={draft.rag.qdrantUrl} onChange={(e) => setField('rag', 'qdrantUrl', e.target.value)} />
          <TextField label="Qdrant Collection" value={draft.rag.qdrantCollection} onChange={(e) => setField('rag', 'qdrantCollection', e.target.value)} />
          <SecretField
            label="Qdrant API Key"
            value={draft.rag.qdrantApiKey}
            onChange={(v) => setField('rag', 'qdrantApiKey', v)}
            configured={Boolean(read(rag, 'qdrantApiKeyConfigured'))}
            clearChecked={draft.rag.clearQdrantApiKey}
            onClearChange={(v) => setField('rag', 'clearQdrantApiKey', v)}
          />
          <TextField label="代理 URL（可选）" value={draft.rag.proxyUrl} onChange={(e) => setField('rag', 'proxyUrl', e.target.value)} />
          <TextField label="Jina URL" value={draft.rag.jinaUrl} onChange={(e) => setField('rag', 'jinaUrl', e.target.value)} />
          <TextField label="Jina 模型" value={draft.rag.jinaModel} onChange={(e) => setField('rag', 'jinaModel', e.target.value)} />
          <SecretField
            label="Jina API Key"
            value={draft.rag.jinaApiKey}
            onChange={(v) => setField('rag', 'jinaApiKey', v)}
            configured={Boolean(read(rag, 'jinaApiKeyConfigured'))}
            clearChecked={draft.rag.clearJinaApiKey}
            onClearChange={(v) => setField('rag', 'clearJinaApiKey', v)}
          />
          <SelectField label="嵌入 Provider" value={draft.rag.embeddingProvider} onChange={(e) => setField('rag', 'embeddingProvider', e.target.value)}>
            <option value="auto">auto</option>
            <option value="jina">jina</option>
            <option value="openai">openai 兼容</option>
            <option value="deepseek">deepseek</option>
            <option value="local_hash">local_hash</option>
          </SelectField>
          <TextField label="本地嵌入维度" type="number" min="32" max="4096" value={draft.rag.localEmbeddingDimensions} onChange={(e) => setField('rag', 'localEmbeddingDimensions', e.target.value)} />
          <TextField label="超时（秒）" type="number" min="1" max="120" value={draft.rag.timeoutSeconds} onChange={(e) => setField('rag', 'timeoutSeconds', e.target.value)} />
        </div>
        <div className="mt-4 flex flex-wrap items-end gap-x-6 gap-y-3 rounded-xl bg-ink-50 p-4">
          <label className="flex items-center gap-2 text-sm text-ink-700">
            <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={draft.rag.mqeEnabled} onChange={(e) => setField('rag', 'mqeEnabled', e.target.checked)} />
            MQE 多查询扩展
          </label>
          <label className="flex items-center gap-2 text-sm text-ink-700">
            <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={draft.rag.hydeEnabled} onChange={(e) => setField('rag', 'hydeEnabled', e.target.checked)} />
            HyDE
          </label>
          <label className="flex items-center gap-2 text-sm text-ink-700">
            <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={draft.rag.llmRerankEnabled} onChange={(e) => setField('rag', 'llmRerankEnabled', e.target.checked)} />
            LLM 复排
          </label>
          <label className="block w-28">
            <span className="mb-1 block text-xs font-medium text-ink-500">复排 TopN</span>
            <input type="number" min="3" max="30" className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={draft.rag.llmRerankTopN} onChange={(e) => setField('rag', 'llmRerankTopN', e.target.value)} />
          </label>
          <label className="block w-36">
            <span className="mb-1 block text-xs font-medium text-ink-500">扩展生成方式</span>
            <select className="h-9 w-full rounded-xl border-0 bg-white px-2.5 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={draft.rag.queryExpansionProvider} onChange={(e) => setField('rag', 'queryExpansionProvider', e.target.value)}>
              <option value="local">local</option>
              <option value="llm">llm</option>
            </select>
          </label>
        </div>
      </Section>

      <Section title="Neo4j 图数据库" description="图谱连接参数（状态与控制台见「图谱 Neo4j」页）" defaultOpen={false}>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField label="Neo4j URI" value={draft.neo4j.url} onChange={(e) => setField('neo4j', 'url', e.target.value)} />
          <SecretField
            label="密码 / API Key"
            value={draft.neo4j.apiKey}
            onChange={(v) => setField('neo4j', 'apiKey', v)}
            configured={Boolean(read(neo4j, 'apiKeyConfigured') ?? read(neo4j, 'passwordConfigured'))}
            clearChecked={draft.neo4j.clearApiKey}
            onClearChange={(v) => setField('neo4j', 'clearApiKey', v)}
          />
          <TextField label="用户名" value={draft.neo4j.username} onChange={(e) => setField('neo4j', 'username', e.target.value)} />
          <TextField label="Database" value={draft.neo4j.database} onChange={(e) => setField('neo4j', 'database', e.target.value)} />
          <SelectField label="Transport" value={draft.neo4j.transport} onChange={(e) => setField('neo4j', 'transport', e.target.value)}>
            <option value="auto">auto</option>
            <option value="bolt">bolt</option>
            <option value="http">http</option>
          </SelectField>
          <TextField label="代理 URL（可选）" value={draft.neo4j.proxyUrl} onChange={(e) => setField('neo4j', 'proxyUrl', e.target.value)} />
          <TextField label="超时（秒）" type="number" min="1" max="120" value={draft.neo4j.timeoutSeconds} onChange={(e) => setField('neo4j', 'timeoutSeconds', e.target.value)} />
          <label className="flex items-center gap-2 self-end pb-2 text-sm text-ink-700">
            <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={draft.neo4j.insecureSkipVerify} onChange={(e) => setField('neo4j', 'insecureSkipVerify', e.target.checked)} />
            跳过 TLS 证书校验
          </label>
        </div>
      </Section>
    </div>
  )
}

/* ---------------- 工作台配置（modelOptions / 分页） ---------------- */

function PlatformConfigSection() {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getPlatformConfig(), [])
  const [draft, setDraft] = useState(null)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState(null)
  const [flash, showFlash] = useFlash(2000)

  if (!loading && !error && data && draft === null) {
    const options = read(data, 'modelOptions') ?? []
    setDraft({
      modelOptions: options.length > 0 ? options.map((o) => ({ ...o })) : [{ value: 'balanced', label: '均衡模式', description: '', enabled: true }],
      listingPageSizeOptions: read(data, 'listingPageSizeOptions') ?? [5, 10],
      defaultListingPageSize: read(data, 'defaultListingPageSize') ?? 5,
    })
  }

  const setOption = (index, key, value) =>
    setDraft((prev) => {
      const modelOptions = prev.modelOptions.map((option, i) => (i === index ? { ...option, [key]: value } : option))
      return { ...prev, modelOptions }
    })

  const addOption = () =>
    setDraft((prev) => ({
      ...prev,
      modelOptions: [
        ...prev.modelOptions,
        { value: `custom-${Date.now().toString(36)}`, label: '自定义模型', description: '', enabled: true },
      ],
    }))

  const removeOption = (index) =>
    setDraft((prev) => ({
      ...prev,
      modelOptions: prev.modelOptions.length > 1 ? prev.modelOptions.filter((_, i) => i !== index) : prev.modelOptions,
    }))

  const togglePageSize = (size) =>
    setDraft((prev) => {
      const has = prev.listingPageSizeOptions.includes(size)
      const next = has ? prev.listingPageSizeOptions.filter((v) => v !== size) : [...prev.listingPageSizeOptions, size].sort((a, b) => a - b)
      const options = next.length > 0 ? next : [5]
      return {
        ...prev,
        listingPageSizeOptions: options,
        defaultListingPageSize: options.includes(prev.defaultListingPageSize) ? prev.defaultListingPageSize : options[0],
      }
    })

  const handleSave = async () => {
    setSaving(true)
    setSaveError(null)
    try {
      await adminService.updatePlatformConfig({
        modelOptions: draft.modelOptions,
        listingPageSizeOptions: draft.listingPageSizeOptions,
        defaultListingPageSize: draft.defaultListingPageSize,
      })
      showFlash('工作台配置已保存，已即时生效')
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
        title="工作台配置"
        description="用户端可选模型列表与推荐分页大小"
        actions={<Button size="sm" loading={saving} onClick={handleSave} disabled={!draft}>保存配置</Button>}
      />
      <CardBody className="space-y-4">
        {loading && <LoadingBlock text="正在加载工作台配置…" className="py-8" />}
        {error && <ErrorBar error={error} onRetry={reload} />}
        {saveError && <ErrorBar error={saveError} />}
        <SuccessBar message={flash} />

        {draft && (
          <>
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium text-ink-700">模型选项（modelOptions）</p>
                <Button size="sm" variant="ghost" onClick={addOption}>
                  + 添加模型
                </Button>
              </div>
              <ul className="space-y-2">
                {draft.modelOptions.map((option, index) => (
                  <li key={index} className="grid grid-cols-1 items-end gap-2 rounded-xl bg-ink-50 p-3 sm:grid-cols-[1fr_1fr_1.4fr_auto_auto]">
                    <label className="block">
                      <span className="mb-1 block text-xs font-medium text-ink-500">value（模型 ID）</span>
                      <input className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={option.value ?? ''} onChange={(e) => setOption(index, 'value', e.target.value)} />
                    </label>
                    <label className="block">
                      <span className="mb-1 block text-xs font-medium text-ink-500">名称</span>
                      <input className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={option.label ?? ''} onChange={(e) => setOption(index, 'label', e.target.value)} />
                    </label>
                    <label className="block">
                      <span className="mb-1 block text-xs font-medium text-ink-500">描述</span>
                      <input className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500" value={option.description ?? ''} onChange={(e) => setOption(index, 'description', e.target.value)} />
                    </label>
                    <label className="flex h-9 items-center gap-1.5 text-xs text-ink-600">
                      <input type="checkbox" className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500" checked={option.enabled !== false} onChange={(e) => setOption(index, 'enabled', e.target.checked)} />
                      启用
                    </label>
                    <button
                      type="button"
                      className="h-9 rounded-full px-3 text-xs font-medium text-rose-600 transition hover:bg-rose-50 disabled:cursor-not-allowed disabled:text-ink-300"
                      disabled={draft.modelOptions.length <= 1}
                      onClick={() => removeOption(index)}
                    >
                      删除
                    </button>
                  </li>
                ))}
              </ul>
            </div>

            <div className="flex flex-wrap items-end gap-6">
              <div>
                <p className="mb-1.5 text-sm font-medium text-ink-700">每页条数选项</p>
                <div className="flex gap-4">
                  {[5, 10].map((size) => (
                    <label key={size} className="flex items-center gap-2 text-sm text-ink-700">
                      <input
                        type="checkbox"
                        className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                        checked={draft.listingPageSizeOptions.includes(size)}
                        onChange={() => togglePageSize(size)}
                      />
                      每页 {size} 条
                    </label>
                  ))}
                </div>
              </div>
              <SelectField
                label="默认每页条数"
                className="w-40"
                value={draft.defaultListingPageSize}
                onChange={(e) => setDraft((prev) => ({ ...prev, defaultListingPageSize: Number(e.target.value) }))}
              >
                {draft.listingPageSizeOptions.map((size) => (
                  <option key={size} value={size}>
                    {size}
                  </option>
                ))}
              </SelectField>
              <Badge tone="neutral">当前默认：{draft.defaultListingPageSize}</Badge>
            </div>
          </>
        )}
      </CardBody>
    </Card>
  )
}

/**
 * 集成配置：LLM / RAG / Neo4j 系统集成 + 工作台配置。
 */
function AdminIntegrationsPage() {
  return (
    <div className="space-y-8 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">集成配置</h1>
        <p className="mt-1 text-sm text-ink-400">外部服务接入与用户端工作台默认配置</p>
      </div>

      <IntegrationsSection />
      <PlatformConfigSection />
    </div>
  )
}

export default AdminIntegrationsPage

import { Link } from 'react-router-dom'

import { Card, CardBody, CardHeader } from '../../components/ui/Card.jsx'
import { EmptyState, LoadingBlock } from '../../components/ui/Feedback.jsx'
import StatCard from '../../features/admin/components/StatCard.jsx'
import { ErrorBar } from '../../features/admin/components/Notice.jsx'
import { useAsyncData } from '../../features/admin/hooks.js'
import { pick } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

/** 已知计数指标的中文标签映射，其余字段动态兜底展示 */
const COUNT_LABELS = {
  users: '注册用户',
  verifiedUsers: '已验证用户',
  verified_users: '已验证用户',
  favorites: '收藏记录',
  searchHistory: '搜索历史',
  search_history: '搜索历史',
  listings: '已发布房源',
  activeListings: '在架房源',
  candidates: '采集候选',
  pendingCandidates: '待审核候选',
  audits: '检索审计',
  retrievalAudits: '检索审计',
  traces: 'Agent 追踪',
  agentTraces: 'Agent 追踪',
  notifications: '公告',
  interactions: '交互记录',
  sources: '数据源',
}

const QUICK_LINKS = [
  { to: '/admin/ingestion', title: '采集中心', desc: '爬虫插件、调度与候选审核', icon: '📥' },
  { to: '/admin/listings', title: '房源管理', desc: '已发布房源检索与编辑', icon: '🏠' },
  { to: '/admin/vector-store', title: '向量库', desc: 'Qdrant 状态、索引与语义检索', icon: '🧭' },
  { to: '/admin/graph-store', title: '知识图谱', desc: 'Neo4j 配置与 Cypher 控制台', icon: '🕸️' },
  { to: '/admin/audits', title: '检索审计', desc: '召回明细与回放对比', icon: '🔍' },
  { to: '/admin/traces', title: 'Agent 追踪', desc: '执行链路与步骤时间线', icon: '🛰️' },
  { to: '/admin/logs', title: '系统日志', desc: 'API / 应用 / LLM 调用日志', icon: '📄' },
  { to: '/admin/integrations', title: '集成配置', desc: 'LLM · RAG · Neo4j 一站式配置', icon: '⚙️' },
]

function labelForCount(key) {
  return COUNT_LABELS[key] ?? key
}

function AdminOverviewPage() {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getOverview(), [])

  const counts = pick(data, 'counts') ?? {}
  const countEntries = Object.entries(counts).filter(([, value]) => typeof value === 'number')

  return (
    <div className="space-y-6 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">控制台总览</h1>
        <p className="mt-1 text-sm text-ink-400">平台核心指标与各管理面板快捷入口</p>
      </div>

      {error && <ErrorBar error={error} onRetry={reload} />}
      {loading && <LoadingBlock text="正在加载总览指标…" />}

      {!loading && !error && (
        <>
          {countEntries.length === 0 ? (
            <Card>
              <EmptyState title="暂无指标数据" description="后端未返回 counts 统计，请检查 /api/admin/overview" />
            </Card>
          ) : (
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-5">
              {countEntries.map(([key, value]) => (
                <StatCard key={key} label={labelForCount(key)} value={value.toLocaleString()} />
              ))}
            </div>
          )}

          <Card>
            <CardHeader title="快捷入口" description="进入各管理面板" />
            <CardBody>
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
                {QUICK_LINKS.map((link) => (
                  <Link
                    key={link.to}
                    to={link.to}
                    className="group flex items-start gap-3 rounded-2xl p-3.5 ring-1 ring-white/[0.06] transition hover:-translate-y-0.5 hover:shadow-card hover:ring-brand-400/40"
                  >
                    <span className="text-xl" aria-hidden="true">{link.icon}</span>
                    <span className="min-w-0">
                      <span className="block text-sm font-semibold text-ink-900 group-hover:text-brand-200">
                        {link.title}
                      </span>
                      <span className="mt-0.5 block text-xs leading-5 text-ink-400">{link.desc}</span>
                    </span>
                  </Link>
                ))}
              </div>
            </CardBody>
          </Card>
        </>
      )}
    </div>
  )
}

export default AdminOverviewPage

import Badge from '../../components/ui/Badge.jsx'
import { boundScore } from './detailUtils.js'

function scoreTone(score) {
  if (score >= 75) return 'success'
  if (score >= 50) return 'brand'
  return 'warning'
}

function EvaluationCard({ title, score, summary, children }) {
  const bounded = boundScore(score)
  return (
    <section className="flex flex-col rounded-2xl bg-surface p-4 shadow-card ring-1 ring-white/[0.06]">
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-sm font-semibold text-ink-900">{title}</h3>
        <Badge tone={scoreTone(bounded)}>{bounded} 分</Badge>
      </div>
      {summary && <p className="mt-2 text-xs leading-5 text-ink-500">{summary}</p>}
      {children}
    </section>
  )
}

/**
 * 评估卡区：valueIndex / environmentEvaluation / commuteEvaluation，
 * detail 中存在哪个就渲染哪个。
 */
function EvaluationCards({ valueIndex, environmentEvaluation, commuteEvaluation }) {
  if (!valueIndex && !environmentEvaluation && !commuteEvaluation) return null

  return (
    <div className="grid gap-4 md:grid-cols-3" aria-label="房源评估">
      {valueIndex && (
        <EvaluationCard title="价值指数" score={valueIndex.score} summary={valueIndex.summary}>
          {Array.isArray(valueIndex.factors) && valueIndex.factors.length > 0 && (
            <dl className="mt-3 space-y-1.5 border-t border-ink-100 pt-3">
              {valueIndex.factors.map((factor) => (
                <div key={`${factor.label}-${factor.value}`} className="flex items-baseline justify-between gap-2 text-xs">
                  <dt className="shrink-0 text-ink-400">{factor.label}</dt>
                  <dd className="text-right font-medium text-ink-700">{factor.value}</dd>
                </div>
              ))}
            </dl>
          )}
          {Array.isArray(valueIndex.evidence) && valueIndex.evidence.length > 0 && (
            <p className="mt-2 text-[11px] leading-4 text-ink-400">参考：{valueIndex.evidence[0]}</p>
          )}
        </EvaluationCard>
      )}

      {environmentEvaluation && (
        <EvaluationCard
          title="环境评估"
          score={environmentEvaluation.score}
          summary={environmentEvaluation.summary}
        >
          {Array.isArray(environmentEvaluation.factors) && environmentEvaluation.factors.length > 0 && (
            <ul className="mt-3 flex flex-wrap gap-1.5 border-t border-ink-100 pt-3">
              {environmentEvaluation.factors.map((factor) => (
                <li key={factor}>
                  <Badge>{factor}</Badge>
                </li>
              ))}
            </ul>
          )}
        </EvaluationCard>
      )}

      {commuteEvaluation && (
        <EvaluationCard title="通勤评估" score={commuteEvaluation.score} summary={commuteEvaluation.summary}>
          {Array.isArray(commuteEvaluation.factors) && commuteEvaluation.factors.length > 0 && (
            <ul className="mt-3 flex flex-wrap gap-1.5 border-t border-ink-100 pt-3">
              {commuteEvaluation.factors.map((factor) => (
                <li key={factor}>
                  <Badge tone="info">{factor}</Badge>
                </li>
              ))}
            </ul>
          )}
          {commuteEvaluation.routeNote && (
            <p className="mt-2 text-[11px] leading-4 text-ink-400">{commuteEvaluation.routeNote}</p>
          )}
        </EvaluationCard>
      )}
    </div>
  )
}

export default EvaluationCards

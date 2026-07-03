import { boundScore } from './detailUtils.js'

/** 环形评分 */
function ScoreRing({ score = 0, size = 88, label = '综合评分' }) {
  const bounded = boundScore(score)
  const radius = 40
  const circumference = 2 * Math.PI * radius

  return (
    <div className="relative inline-flex items-center justify-center" style={{ width: size, height: size }}>
      <svg viewBox="0 0 100 100" className="h-full w-full -rotate-90" aria-hidden="true">
        <circle cx="50" cy="50" r={radius} fill="none" strokeWidth="9" className="stroke-ink-100" />
        <circle
          cx="50"
          cy="50"
          r={radius}
          fill="none"
          strokeWidth="9"
          strokeLinecap="round"
          className="stroke-brand-500"
          strokeDasharray={circumference}
          strokeDashoffset={circumference * (1 - bounded / 100)}
        />
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center" aria-label={`${label} ${bounded} 分`}>
        <span className="text-xl font-semibold leading-none text-ink-900">{bounded}</span>
        <span className="mt-1 text-[10px] text-ink-400">{label}</span>
      </div>
    </div>
  )
}

export default ScoreRing

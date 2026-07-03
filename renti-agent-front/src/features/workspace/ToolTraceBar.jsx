import { useState } from 'react'

const OK_STATUSES = new Set(['ok', 'success', 'done', 'ready'])
const ERROR_STATUSES = new Set(['error', 'failed', 'fail'])

function statusDotClass(status) {
  const value = String(status || '').toLowerCase()
  if (OK_STATUSES.has(value)) return 'bg-emerald-500'
  if (ERROR_STATUSES.has(value)) return 'bg-rose-500'
  return 'bg-ink-300'
}

/** 工具调用步骤条：可折叠，逐条展示 tool / status / summary */
function ToolTraceBar({ trace, defaultOpen = false }) {
  const [open, setOpen] = useState(defaultOpen)
  const items = Array.isArray(trace) ? trace : []
  if (items.length === 0) return null

  return (
    <div className="rounded-xl bg-ink-50 ring-1 ring-ink-100">
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        className="flex w-full items-center justify-between px-3 py-2 text-xs font-medium text-ink-600 transition hover:text-ink-900"
      >
        <span className="flex items-center gap-1.5">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-3.5 w-3.5" aria-hidden="true">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M11.42 15.17 17.25 21A2.652 2.652 0 0 0 21 17.25l-5.877-5.877M11.42 15.17l2.496-3.03c.317-.384.74-.626 1.208-.766M11.42 15.17l-4.655 5.653a2.548 2.548 0 1 1-3.586-3.586l6.837-5.63m5.108-.233c.55-.164 1.163-.188 1.743-.14a4.5 4.5 0 0 0 4.486-6.336l-3.276 3.277a3.004 3.004 0 0 1-2.25-2.25l3.276-3.276a4.5 4.5 0 0 0-6.336 4.486c.091 1.076-.071 2.264-.904 2.95l-.102.085"
            />
          </svg>
          执行步骤 · {items.length} 步
        </span>
        <svg
          viewBox="0 0 20 20"
          fill="currentColor"
          className={['h-3.5 w-3.5 text-ink-400 transition-transform', open ? 'rotate-180' : ''].join(' ')}
          aria-hidden="true"
        >
          <path
            fillRule="evenodd"
            d="M5.22 7.22a.75.75 0 0 1 1.06 0L10 10.94l3.72-3.72a.75.75 0 1 1 1.06 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0L5.22 8.28a.75.75 0 0 1 0-1.06Z"
            clipRule="evenodd"
          />
        </svg>
      </button>
      {open && (
        <ol className="space-y-2 border-t border-ink-100 px-3 py-2.5">
          {items.map((item, index) => (
            <li key={`${item.tool}-${index}`} className="flex items-start gap-2">
              <span
                className={['mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full', statusDotClass(item.status)].join(' ')}
                aria-hidden="true"
              />
              <div className="min-w-0">
                <p className="text-xs font-medium text-ink-700">
                  {item.tool || `步骤 ${index + 1}`}
                  <span className="ml-1.5 font-normal text-ink-400">{item.status}</span>
                </p>
                {item.summary && <p className="mt-0.5 text-xs leading-5 text-ink-500">{item.summary}</p>}
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

export default ToolTraceBar

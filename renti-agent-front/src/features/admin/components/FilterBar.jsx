/**
 * 筛选条：一行排布筛选控件 + 提交按钮，回车触发查询。
 */
function FilterBar({ onSubmit, children, className = '' }) {
  return (
    <form
      className={['flex flex-wrap items-end gap-3', className].join(' ')}
      onSubmit={(event) => {
        event.preventDefault()
        onSubmit?.()
      }}
    >
      {children}
    </form>
  )
}

/** 筛选条内的紧凑输入框 */
export function FilterInput({ label, className = 'w-44', ...rest }) {
  return (
    <label className={['block', className].join(' ')}>
      <span className="mb-1 block text-xs font-medium text-ink-500">{label}</span>
      <input
        className="h-9 w-full rounded-xl border-0 bg-white px-3 text-sm text-ink-900 shadow-sm ring-1 ring-inset ring-ink-200 placeholder:text-ink-300 focus:ring-2 focus:ring-brand-500"
        {...rest}
      />
    </label>
  )
}

/** 筛选条内的紧凑下拉框 */
export function FilterSelect({ label, className = 'w-40', children, ...rest }) {
  return (
    <label className={['block', className].join(' ')}>
      <span className="mb-1 block text-xs font-medium text-ink-500">{label}</span>
      <select
        className="h-9 w-full rounded-xl border-0 bg-white px-2.5 text-sm text-ink-900 shadow-sm ring-1 ring-inset ring-ink-200 focus:ring-2 focus:ring-brand-500"
        {...rest}
      >
        {children}
      </select>
    </label>
  )
}

export default FilterBar

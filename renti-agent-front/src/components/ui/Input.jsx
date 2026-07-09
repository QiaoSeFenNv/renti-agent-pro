import { useId } from 'react'

/**
 * 文本输入框（含可选 label 与错误提示）：暗色内嵌场，聚焦时品牌辉光。
 */
export function TextField({
  label,
  error,
  hint,
  className = '',
  inputClassName = '',
  id: idProp,
  ...rest
}) {
  const autoId = useId()
  const id = idProp ?? autoId
  return (
    <div className={className}>
      {label && (
        <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-ink-700">
          {label}
        </label>
      )}
      <input
        id={id}
        className={[
          'h-11 w-full rounded-xl border-0 bg-black/30 px-3.5 text-sm text-ink-900 ring-1 ring-inset transition duration-200',
          'placeholder:text-ink-300 focus:bg-black/45 focus:shadow-glow focus:ring-2',
          error ? 'ring-rose-500/50 focus:ring-rose-500' : 'ring-white/10 focus:ring-brand-500/80',
          inputClassName,
        ].join(' ')}
        aria-invalid={Boolean(error)}
        {...rest}
      />
      {error ? (
        <p className="mt-1.5 text-xs text-rose-700">{error}</p>
      ) : hint ? (
        <p className="mt-1.5 text-xs text-ink-400">{hint}</p>
      ) : null}
    </div>
  )
}

/** 下拉框自定义箭头（原生箭头样式无法控制，统一替换） */
export function SelectChevron({ className = 'right-3' }) {
  return (
    <svg
      viewBox="0 0 20 20"
      fill="currentColor"
      aria-hidden="true"
      className={[
        'pointer-events-none absolute top-1/2 h-4 w-4 -translate-y-1/2 text-ink-400 transition-colors',
        className,
      ].join(' ')}
    >
      <path
        fillRule="evenodd"
        d="M5.23 7.21a.75.75 0 0 1 1.06.02L10 11.17l3.71-3.94a.75.75 0 1 1 1.08 1.04l-4.25 4.5a.75.75 0 0 1-1.08 0l-4.25-4.5a.75.75 0 0 1 .02-1.06Z"
        clipRule="evenodd"
      />
    </svg>
  )
}

/** 下拉选择框，样式与 TextField 对齐 */
export function SelectField({ label, error, className = '', id: idProp, children, ...rest }) {
  const autoId = useId()
  const id = idProp ?? autoId
  return (
    <div className={className}>
      {label && (
        <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-ink-700">
          {label}
        </label>
      )}
      <span className="relative block">
        <select
          id={id}
          className={[
            'h-11 w-full cursor-pointer appearance-none truncate rounded-xl border-0 bg-black/30 pl-3.5 pr-9 text-sm text-ink-900',
            'ring-1 ring-inset transition duration-200 focus:bg-black/45 focus:shadow-glow focus:ring-2',
            error ? 'ring-rose-500/50' : 'ring-white/10 hover:ring-white/20 focus:ring-brand-500/80',
          ].join(' ')}
          {...rest}
        >
          {children}
        </select>
        <SelectChevron />
      </span>
      {error && <p className="mt-1.5 text-xs text-rose-700">{error}</p>}
    </div>
  )
}

export default TextField

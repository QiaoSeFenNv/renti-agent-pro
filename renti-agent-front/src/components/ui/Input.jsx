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
      <select
        id={id}
        className={[
          'h-11 w-full rounded-xl border-0 bg-black/30 px-3 text-sm text-ink-900 ring-1 ring-inset transition duration-200',
          'focus:bg-black/45 focus:shadow-glow focus:ring-2',
          error ? 'ring-rose-500/50' : 'ring-white/10 focus:ring-brand-500/80',
        ].join(' ')}
        {...rest}
      >
        {children}
      </select>
      {error && <p className="mt-1.5 text-xs text-rose-700">{error}</p>}
    </div>
  )
}

export default TextField

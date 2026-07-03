import { useId } from 'react'

/**
 * 文本输入框（含可选 label 与错误提示）。
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
          'h-11 w-full rounded-xl border-0 bg-white px-3.5 text-sm text-ink-900 shadow-sm ring-1 ring-inset transition',
          'placeholder:text-ink-300 focus:ring-2 focus:ring-brand-500',
          error ? 'ring-rose-300 focus:ring-rose-500' : 'ring-ink-200',
          inputClassName,
        ].join(' ')}
        aria-invalid={Boolean(error)}
        {...rest}
      />
      {error ? (
        <p className="mt-1.5 text-xs text-rose-600">{error}</p>
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
          'h-11 w-full rounded-xl border-0 bg-white px-3 text-sm text-ink-900 shadow-sm ring-1 ring-inset transition',
          'focus:ring-2 focus:ring-brand-500',
          error ? 'ring-rose-300' : 'ring-ink-200',
        ].join(' ')}
        {...rest}
      >
        {children}
      </select>
      {error && <p className="mt-1.5 text-xs text-rose-600">{error}</p>}
    </div>
  )
}

export default TextField

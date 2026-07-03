/**
 * 基础按钮：统一全站按钮形态。
 *
 * @param {object} props
 * @param {'primary'|'secondary'|'ghost'|'danger'|'dark'} [props.variant]
 * @param {'sm'|'md'|'lg'} [props.size]
 * @param {boolean} [props.loading] 加载中（禁用并显示旋转图标）
 * @param {boolean} [props.block] 占满整行
 */
function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  block = false,
  disabled = false,
  className = '',
  children,
  type = 'button',
  ...rest
}) {
  const variants = {
    primary: 'bg-brand-600 text-white hover:bg-brand-700 active:bg-brand-800 shadow-sm',
    secondary: 'bg-white text-ink-700 ring-1 ring-inset ring-ink-200 hover:bg-ink-50 hover:text-ink-900',
    ghost: 'text-ink-600 hover:bg-ink-100 hover:text-ink-900',
    danger: 'bg-rose-600 text-white hover:bg-rose-700',
    dark: 'bg-ink-950 text-white hover:bg-ink-800',
  }
  const sizes = {
    sm: 'h-8 px-3 text-xs gap-1.5',
    md: 'h-10 px-4 text-sm gap-2',
    lg: 'h-12 px-6 text-base gap-2',
  }

  return (
    <button
      type={type}
      disabled={disabled || loading}
      className={[
        'inline-flex items-center justify-center rounded-full font-medium transition-colors duration-150',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500',
        'disabled:cursor-not-allowed disabled:opacity-50',
        variants[variant] ?? variants.primary,
        sizes[size] ?? sizes.md,
        block ? 'w-full' : '',
        className,
      ].join(' ')}
      {...rest}
    >
      {loading && (
        <svg className="h-4 w-4 animate-spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
        </svg>
      )}
      {children}
    </button>
  )
}

export default Button

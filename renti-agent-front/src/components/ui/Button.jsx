/**
 * 基础按钮：统一全站按钮形态（黑色科技风）。
 *
 * primary   品牌渐变 + 辉光 + hover 光泽扫过，唯一的高权重行动点
 * secondary 玻璃拟态描边按钮，次级操作
 * ghost     纯文字弱按钮
 * danger    破坏性操作
 * dark      反白按钮（暗底上的最高对比，如 hero 次按钮）
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
    primary: [
      'shine bg-brand-gradient text-white shadow-glow',
      'hover:shadow-glow-lg hover:brightness-110 hover:-translate-y-px',
      'active:translate-y-0 active:brightness-95',
    ].join(' '),
    secondary: [
      'bg-white/[0.06] text-ink-800 ring-1 ring-inset ring-white/10 backdrop-blur',
      'hover:bg-white/[0.1] hover:text-white hover:ring-white/25',
      'active:bg-white/[0.08]',
    ].join(' '),
    ghost: 'text-ink-500 hover:bg-white/[0.06] hover:text-ink-900',
    danger: [
      'bg-rose-600 text-white shadow-[0_0_20px_-6px_rgba(244,63,94,0.6)]',
      'hover:bg-rose-500 hover:-translate-y-px active:translate-y-0',
    ].join(' '),
    dark: 'bg-ink-950 text-surface-deep hover:bg-white active:bg-ink-800',
  }
  const sizes = {
    sm: 'h-8 px-3.5 text-xs gap-1.5',
    md: 'h-10 px-5 text-sm gap-2',
    lg: 'h-12 px-7 text-base gap-2',
  }

  return (
    <button
      type={type}
      disabled={disabled || loading}
      className={[
        'relative inline-flex select-none items-center justify-center overflow-hidden rounded-full font-medium',
        'transition-all duration-200 ease-out',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-400',
        'disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none disabled:hover:translate-y-0 disabled:hover:brightness-100',
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
      <span className="relative z-10 inline-flex items-center gap-[inherit]">{children}</span>
    </button>
  )
}

export default Button

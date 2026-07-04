/**
 * 卡片容器：深色面板、圆角、内侧高光 + 深阴影，全站统一的信息承载单元。
 */
export function Card({ className = '', hover = false, children, ...rest }) {
  return (
    <div
      className={[
        'rounded-2xl bg-surface shadow-card ring-1 ring-white/[0.06]',
        hover
          ? 'transition duration-300 hover:-translate-y-1 hover:shadow-float hover:ring-white/[0.14]'
          : '',
        className,
      ].join(' ')}
      {...rest}
    >
      {children}
    </div>
  )
}

/** 卡片标题行：左侧标题+描述，右侧操作区 */
export function CardHeader({ title, description, actions, className = '' }) {
  return (
    <div className={['flex flex-wrap items-start justify-between gap-3 px-5 pt-5', className].join(' ')}>
      <div className="min-w-0">
        <h3 className="text-sm font-semibold text-ink-900">{title}</h3>
        {description && <p className="mt-0.5 text-xs leading-5 text-ink-500">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  )
}

export function CardBody({ className = '', children }) {
  return <div className={['px-5 py-4', className].join(' ')}>{children}</div>
}

export default Card

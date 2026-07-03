/**
 * 卡片容器：白底、圆角、柔和阴影，全站统一的信息承载单元。
 */
export function Card({ className = '', hover = false, children, ...rest }) {
  return (
    <div
      className={[
        'rounded-2xl bg-white shadow-card ring-1 ring-ink-100/60',
        hover ? 'transition duration-200 hover:-translate-y-0.5 hover:shadow-float' : '',
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

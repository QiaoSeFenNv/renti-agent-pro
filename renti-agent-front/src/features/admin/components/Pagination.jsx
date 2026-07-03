/**
 * 统一分页条：页码制（page/pageSize/totalPages），与后端分页响应对齐。
 *
 * @param {object} props
 * @param {number} props.page 当前页（1 基）
 * @param {number} [props.totalPages] 总页数（未知则用 hasMore 控制下一页）
 * @param {number} [props.total] 总条数（仅展示）
 * @param {boolean} [props.hasMore] totalPages 未知时，是否可翻下一页
 * @param {(page: number) => void} props.onChange
 */
function Pagination({ page = 1, totalPages, total, hasMore, onChange, className = '' }) {
  const known = typeof totalPages === 'number' && totalPages > 0
  const canPrev = page > 1
  const canNext = known ? page < totalPages : Boolean(hasMore)

  const btnCls = (enabled) =>
    [
      'inline-flex h-8 items-center rounded-full px-3 text-xs font-medium ring-1 ring-inset transition',
      enabled
        ? 'bg-white text-ink-700 ring-ink-200 hover:bg-ink-50'
        : 'cursor-not-allowed bg-ink-50 text-ink-300 ring-ink-100',
    ].join(' ')

  return (
    <div className={['flex items-center justify-between gap-3 px-4 py-3 text-xs text-ink-400', className].join(' ')}>
      <span>
        {typeof total === 'number' ? `共 ${total} 条 · ` : ''}
        第 {page}
        {known ? `/${totalPages}` : ''} 页
      </span>
      <div className="flex items-center gap-2">
        <button type="button" className={btnCls(canPrev)} disabled={!canPrev} onClick={() => onChange(page - 1)}>
          上一页
        </button>
        <button type="button" className={btnCls(canNext)} disabled={!canNext} onClick={() => onChange(page + 1)}>
          下一页
        </button>
      </div>
    </div>
  )
}

export default Pagination

import { EmptyState, LoadingBlock } from '../../../components/ui/Feedback.jsx'

/**
 * 统一数据表：白卡包裹、表头小写灰、行 hover。
 *
 * @param {object} props
 * @param {Array<{key: string, label: string, className?: string, align?: 'left'|'right'|'center', render?: (row, index) => any}>} props.columns
 * @param {Array<object>} props.rows
 * @param {(row: object, index: number) => any} [props.rowKey]
 * @param {(row: object) => void} [props.onRowClick]
 * @param {boolean} [props.loading]
 * @param {string} [props.emptyText]
 */
function DataTable({ columns, rows = [], rowKey, onRowClick, loading = false, emptyText = '暂无数据', footer }) {
  const alignCls = (align) =>
    align === 'right' ? 'text-right' : align === 'center' ? 'text-center' : 'text-left'

  return (
    <div className="overflow-hidden rounded-2xl bg-white shadow-card ring-1 ring-ink-100/60">
      <div className="overflow-x-auto scrollbar-thin">
        <table className="min-w-full divide-y divide-ink-100 text-sm">
          <thead>
            <tr>
              {columns.map((col) => (
                <th
                  key={col.key}
                  scope="col"
                  className={[
                    'whitespace-nowrap px-4 py-3 text-xs font-semibold uppercase tracking-wide text-ink-400',
                    alignCls(col.align),
                    col.className ?? '',
                  ].join(' ')}
                >
                  {col.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100/80">
            {!loading &&
              rows.map((row, index) => (
                <tr
                  key={rowKey ? rowKey(row, index) : index}
                  className={[
                    'transition-colors',
                    onRowClick ? 'cursor-pointer hover:bg-ink-50' : 'hover:bg-ink-50/60',
                  ].join(' ')}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                >
                  {columns.map((col) => (
                    <td
                      key={col.key}
                      className={[
                        'px-4 py-3 align-middle text-ink-700',
                        alignCls(col.align),
                        col.className ?? '',
                      ].join(' ')}
                    >
                      {col.render ? col.render(row, index) : row[col.key] ?? '—'}
                    </td>
                  ))}
                </tr>
              ))}
          </tbody>
        </table>
      </div>
      {loading && <LoadingBlock className="py-12" />}
      {!loading && rows.length === 0 && <EmptyState title={emptyText} className="py-12" />}
      {footer}
    </div>
  )
}

export default DataTable

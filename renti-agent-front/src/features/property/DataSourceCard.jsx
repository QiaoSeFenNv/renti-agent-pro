import { formatDate } from './detailUtils.js'

/** 数据来源卡：provider / 采集与更新时间 / 访问方式 / 可信度说明 / 外链 */
function DataSourceCard({ dataSource }) {
  if (!dataSource) return null
  const links = Array.isArray(dataSource.links) ? dataSource.links.filter((link) => link?.url) : []

  return (
    <section className="rounded-2xl bg-white p-5 shadow-card ring-1 ring-ink-100/60" aria-label="房源数据来源">
      <h3 className="text-sm font-semibold text-ink-900">数据来源</h3>
      <p className="mt-1 text-xs leading-5 text-ink-400">这条记录从哪里来、何时更新，以及如何核验。</p>

      <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 text-xs sm:grid-cols-4">
        <div>
          <dt className="text-ink-400">数据提供方</dt>
          <dd className="mt-0.5 font-medium text-ink-800">{dataSource.provider || '未知'}</dd>
        </div>
        <div>
          <dt className="text-ink-400">来源名称</dt>
          <dd className="mt-0.5 break-all font-medium text-ink-800">
            {dataSource.sourceName || dataSource.source_name || '—'}
          </dd>
        </div>
        <div>
          <dt className="text-ink-400">采集时间</dt>
          <dd className="mt-0.5 font-medium text-ink-800">
            {formatDate(dataSource.collectedAt || dataSource.collected_at)}
          </dd>
        </div>
        <div>
          <dt className="text-ink-400">更新时间</dt>
          <dd className="mt-0.5 font-medium text-ink-800">
            {formatDate(dataSource.updatedAt || dataSource.updated_at)}
          </dd>
        </div>
      </dl>

      {(dataSource.accessMethod || dataSource.access_method) && (
        <p className="mt-3 text-xs leading-5 text-ink-500">{dataSource.accessMethod || dataSource.access_method}</p>
      )}
      {(dataSource.reliabilityNote || dataSource.reliability_note) && (
        <p className="mt-1.5 rounded-xl bg-ink-50 px-3 py-2 text-xs leading-5 text-ink-500">
          {dataSource.reliabilityNote || dataSource.reliability_note}
        </p>
      )}

      {links.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-2">
          {links.map((link) => (
            <a
              key={link.url}
              href={link.url}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 rounded-full bg-white px-3 py-1.5 text-xs font-medium text-brand-600 ring-1 ring-ink-200 transition hover:bg-brand-50 hover:ring-brand-200"
            >
              <svg viewBox="0 0 20 20" fill="currentColor" className="h-3 w-3" aria-hidden="true">
                <path d="M12.232 4.232a2.5 2.5 0 0 1 3.536 3.536l-1.225 1.224a.75.75 0 0 0 1.061 1.06l1.224-1.224a4 4 0 0 0-5.656-5.656l-3 3a4 4 0 0 0 .225 5.865.75.75 0 0 0 .977-1.138 2.5 2.5 0 0 1-.142-3.667l3-3Z" />
                <path d="M11.603 7.963a.75.75 0 0 0-.977 1.138 2.5 2.5 0 0 1 .142 3.667l-3 3a2.5 2.5 0 0 1-3.536-3.536l1.225-1.224a.75.75 0 0 0-1.061-1.06l-1.224 1.224a4 4 0 1 0 5.656 5.656l3-3a4 4 0 0 0-.225-5.865Z" />
              </svg>
              {link.label || '来源链接'}
            </a>
          ))}
        </div>
      )}
    </section>
  )
}

export default DataSourceCard

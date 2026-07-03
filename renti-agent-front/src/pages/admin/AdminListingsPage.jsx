import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import Button from '../../components/ui/Button.jsx'
import { SelectField, TextField } from '../../components/ui/Input.jsx'
import Modal from '../../components/ui/Modal.jsx'
import { LoadingBlock } from '../../components/ui/Feedback.jsx'
import DataTable from '../../features/admin/components/DataTable.jsx'
import Drawer from '../../features/admin/components/Drawer.jsx'
import FilterBar, { FilterInput, FilterSelect } from '../../features/admin/components/FilterBar.jsx'
import JsonBlock from '../../features/admin/components/JsonBlock.jsx'
import { ErrorBar, SuccessBar } from '../../features/admin/components/Notice.jsx'
import Pagination from '../../features/admin/components/Pagination.jsx'
import { useAsyncData, useFlash } from '../../features/admin/hooks.js'
import { extractItems, formatTime, pick, read } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

const PAGE_SIZE = 20
const SPLIT_RE = /[,，/、;；|]+/

/** 拿到记录里嵌套的房源对象（列表项是 {listingId, listing:{...}, status,...} 包装） */
function innerListing(record) {
  return read(record, 'listing') ?? record ?? {}
}

function statusBadge(status) {
  if (status === 'active') return <Badge tone="success">在架</Badge>
  if (status === 'unavailable') return <Badge tone="warning">已下架</Badge>
  return <Badge tone="neutral">{status ?? '—'}</Badge>
}

function InfoRow({ label, children }) {
  return (
    <div className="flex items-start justify-between gap-3 py-1.5 text-sm">
      <span className="shrink-0 text-ink-400">{label}</span>
      <span className="min-w-0 break-all text-right text-ink-800">{children ?? '—'}</span>
    </div>
  )
}

/** 详情抽屉 */
function ListingDrawer({ listingId, onClose, onEdit }) {
  const { loading, error, data, reload } = useAsyncData(
    () => adminService.getListingDetail(listingId),
    [listingId],
  )
  const record = pick(data, 'listing') ?? {}
  const item = innerListing(record)

  return (
    <Drawer
      open
      onClose={onClose}
      title="房源详情"
      subtitle={listingId}
      size="lg"
      footer={(
        <Button size="sm" variant="secondary" onClick={() => onEdit(record)} disabled={loading || Boolean(error)}>
          编辑此房源
        </Button>
      )}
    >
      {loading && <LoadingBlock text="正在加载房源详情…" />}
      {error && <ErrorBar error={error} onRetry={reload} />}
      {!loading && !error && (
        <div className="space-y-6">
          <div className="flex flex-wrap items-center gap-2">
            {statusBadge(read(record, 'status'))}
            {read(record, 'provider') && <Badge tone="neutral">{read(record, 'provider')}</Badge>}
            {read(record, 'sourceName') && <Badge tone="info">{read(record, 'sourceName')}</Badge>}
          </div>

          <section className="space-y-1">
            <h4 className="text-xs font-semibold uppercase tracking-wide text-ink-400">核心字段</h4>
            <div className="divide-y divide-ink-100/80 rounded-xl px-3 ring-1 ring-ink-100">
              <InfoRow label="标题">{read(item, 'title')}</InfoRow>
              <InfoRow label="城市 / 区域">
                {`${read(item, 'city') ?? '—'} · ${read(item, 'district') ?? '—'} · ${read(item, 'businessArea') ?? '—'}`}
              </InfoRow>
              <InfoRow label="小区">{read(item, 'community')}</InfoRow>
              <InfoRow label="租金">
                {read(item, 'rentPrice') !== undefined ? `¥${Number(read(item, 'rentPrice')).toLocaleString()}/月` : '—'}
              </InfoRow>
              <InfoRow label="户型 / 面积">
                {`${read(item, 'layout') ?? '—'} · ${read(item, 'areaSqm') ?? '—'}㎡ · ${read(item, 'rentType') ?? '—'}`}
              </InfoRow>
              <InfoRow label="坐标">
                {read(item, 'longitude') !== undefined
                  ? `${read(item, 'longitude')}, ${read(item, 'latitude')}`
                  : '—'}
              </InfoRow>
              <InfoRow label="地铁">
                {read(item, 'nearestMetro')
                  ? `${read(item, 'nearestMetro')}（${read(item, 'metroDistanceM') ?? '—'}m）`
                  : '—'}
              </InfoRow>
              <InfoRow label="标签">{(read(item, 'tags') ?? []).join('、') || '—'}</InfoRow>
              <InfoRow label="风险标签">{(read(item, 'riskTags') ?? []).join('、') || '—'}</InfoRow>
              <InfoRow label="来源链接">{read(record, 'sourceUrl') ?? read(item, 'sourceUrl')}</InfoRow>
              <InfoRow label="发布 / 更新">
                {`${formatTime(read(record, 'publishedAt'))} / ${formatTime(read(record, 'updatedAt'))}`}
              </InfoRow>
              {read(record, 'status') === 'unavailable' && (
                <InfoRow label="下架原因">{read(record, 'unavailableReason')}</InfoRow>
              )}
            </div>
          </section>

          <section>
            <h4 className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-400">原始数据（Raw JSON）</h4>
            <JsonBlock value={record} />
          </section>
        </div>
      )}
    </Drawer>
  )
}

/** 编辑弹窗 */
function ListingEditModal({ record, onClose, onSaved }) {
  const listingId = read(record, 'listingId')
  const item = innerListing(record)
  const [form, setForm] = useState(() => ({
    title: read(item, 'title', ''),
    city: read(item, 'city', ''),
    district: read(item, 'district', ''),
    businessArea: read(item, 'businessArea', ''),
    community: read(item, 'community', ''),
    rentPrice: read(item, 'rentPrice', ''),
    layout: read(item, 'layout', ''),
    areaSqm: read(item, 'areaSqm', ''),
    rentType: read(item, 'rentType', '整租'),
    tagsText: (read(item, 'tags') ?? []).join('，'),
    riskTagsText: (read(item, 'riskTags') ?? []).join('，'),
    status: read(record, 'status', 'active'),
    unavailableReason: read(record, 'unavailableReason') ?? '',
  }))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)
  const [fieldErrors, setFieldErrors] = useState({})

  const setField = (key, value) => setForm((prev) => ({ ...prev, [key]: value }))
  const splitTags = (text) => text.split(SPLIT_RE).map((tag) => tag.trim()).filter(Boolean)

  const handleSubmit = async () => {
    setSaving(true)
    setError(null)
    setFieldErrors({})
    const payload = {
      status: form.status,
      unavailableReason: form.status === 'unavailable' ? form.unavailableReason : '',
      listing: {
        title: form.title.trim(),
        city: form.city.trim(),
        district: form.district.trim(),
        businessArea: form.businessArea.trim(),
        community: form.community.trim(),
        rentPrice: form.rentPrice === '' ? undefined : Number(form.rentPrice),
        layout: form.layout.trim(),
        areaSqm: form.areaSqm === '' ? undefined : Number(form.areaSqm),
        rentType: form.rentType.trim(),
        tags: splitTags(form.tagsText),
        riskTags: splitTags(form.riskTagsText),
      },
    }
    try {
      const result = await adminService.updateListing(listingId, payload)
      if (result?.ok === false) {
        setFieldErrors(result?.fieldErrors ?? {})
        setError(new Error(result?.summary || '保存失败，请检查字段'))
      } else {
        onSaved('房源已保存')
      }
    } catch (err) {
      setFieldErrors(err?.payload?.fieldErrors ?? {})
      setError(err)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={`编辑房源 · ${listingId}`}
      size="xl"
      footer={(
        <>
          <Button variant="secondary" size="sm" onClick={onClose}>
            取消
          </Button>
          <Button size="sm" loading={saving} onClick={handleSubmit}>
            保存修改
          </Button>
        </>
      )}
    >
      <div className="space-y-4">
        {error && <ErrorBar error={error} />}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField label="标题" className="sm:col-span-2" value={form.title} error={fieldErrors.title} onChange={(e) => setField('title', e.target.value)} />
          <TextField label="城市" value={form.city} error={fieldErrors.city} onChange={(e) => setField('city', e.target.value)} />
          <TextField label="区域" value={form.district} error={fieldErrors.district} onChange={(e) => setField('district', e.target.value)} />
          <TextField label="商圈" value={form.businessArea} error={fieldErrors.businessArea ?? fieldErrors.business_area} onChange={(e) => setField('businessArea', e.target.value)} />
          <TextField label="小区" value={form.community} error={fieldErrors.community} onChange={(e) => setField('community', e.target.value)} />
          <TextField label="租金（元/月）" type="number" min="1" value={form.rentPrice} error={fieldErrors.rentPrice ?? fieldErrors.rent_price} onChange={(e) => setField('rentPrice', e.target.value)} />
          <TextField label="户型" value={form.layout} error={fieldErrors.layout} onChange={(e) => setField('layout', e.target.value)} />
          <TextField label="面积（㎡）" type="number" min="1" value={form.areaSqm} error={fieldErrors.areaSqm ?? fieldErrors.area_sqm} onChange={(e) => setField('areaSqm', e.target.value)} />
          <TextField label="租赁方式" value={form.rentType} error={fieldErrors.rentType ?? fieldErrors.rent_type} onChange={(e) => setField('rentType', e.target.value)} />
          <TextField label="标签（逗号分隔）" className="sm:col-span-2" value={form.tagsText} error={fieldErrors.tags} onChange={(e) => setField('tagsText', e.target.value)} />
          <TextField label="风险标签（逗号分隔）" className="sm:col-span-2" value={form.riskTagsText} error={fieldErrors.riskTags ?? fieldErrors.risk_tags} onChange={(e) => setField('riskTagsText', e.target.value)} />
          <SelectField label="状态" value={form.status} onChange={(e) => setField('status', e.target.value)}>
            <option value="active">在架</option>
            <option value="unavailable">下架</option>
          </SelectField>
          {form.status === 'unavailable' && (
            <TextField label="下架原因" value={form.unavailableReason} error={fieldErrors.unavailableReason ?? fieldErrors.unavailable_reason} onChange={(e) => setField('unavailableReason', e.target.value)} />
          )}
        </div>
      </div>
    </Modal>
  )
}

/**
 * 房源管理：已发布房源的检索、编辑与下架/删除。
 */
function AdminListingsPage() {
  const [status, setStatus] = useState('active')
  const [cityInput, setCityInput] = useState('')
  const [queryInput, setQueryInput] = useState('')
  const [applied, setApplied] = useState({ city: '', query: '' })
  const [page, setPage] = useState(1)

  const [detailId, setDetailId] = useState(null)
  const [editRecord, setEditRecord] = useState(null)
  const [deleting, setDeleting] = useState(null)
  const [deleteBusy, setDeleteBusy] = useState(false)
  const [flash, showFlash] = useFlash()
  const [actionError, setActionError] = useState(null)

  const { loading, error, data, reload } = useAsyncData(
    () =>
      adminService.getListings({
        status,
        city: applied.city || undefined,
        query: applied.query || undefined,
        page,
        limit: PAGE_SIZE,
      }),
    [status, applied, page],
  )

  const listings = extractItems(data)
  const total = pick(data, 'total')
  const totalPages = pick(data, 'totalPages', 'total_pages')

  const handleDelete = async () => {
    setDeleteBusy(true)
    setActionError(null)
    try {
      await adminService.deleteListing(read(deleting, 'listingId'))
      setDeleting(null)
      showFlash('房源已删除')
      reload()
    } catch (err) {
      setActionError(err)
      setDeleting(null)
    } finally {
      setDeleteBusy(false)
    }
  }

  const columns = [
    {
      key: 'listingId',
      label: 'Listing ID',
      className: 'max-w-[160px]',
      render: (row) => <span className="block truncate text-xs text-ink-400">{read(row, 'listingId')}</span>,
    },
    {
      key: 'title',
      label: '标题',
      render: (row) => (
        <span className="font-medium text-ink-900">{read(innerListing(row), 'title') ?? '—'}</span>
      ),
    },
    { key: 'city', label: '城市', render: (row) => read(innerListing(row), 'city') ?? '—' },
    { key: 'businessArea', label: '商圈', render: (row) => read(innerListing(row), 'businessArea') ?? '—' },
    {
      key: 'rentPrice',
      label: '租金',
      align: 'right',
      render: (row) => {
        const price = read(innerListing(row), 'rentPrice')
        return price !== undefined ? (
          <span className="tabular-nums font-medium text-ink-800">¥{Number(price).toLocaleString()}</span>
        ) : (
          <span className="text-ink-400">待补</span>
        )
      },
    },
    { key: 'status', label: '状态', render: (row) => statusBadge(read(row, 'status')) },
    { key: 'updatedAt', label: '更新时间', render: (row) => formatTime(read(row, 'updatedAt')) },
    {
      key: 'actions',
      label: '操作',
      align: 'right',
      render: (row) => (
        <span className="space-x-3 whitespace-nowrap">
          <button
            type="button"
            className="text-xs font-medium text-brand-600 hover:text-brand-700"
            onClick={(e) => {
              e.stopPropagation()
              setDetailId(read(row, 'listingId'))
            }}
          >
            详情
          </button>
          <button
            type="button"
            className="text-xs font-medium text-ink-600 hover:text-ink-800"
            onClick={(e) => {
              e.stopPropagation()
              setEditRecord(row)
            }}
          >
            编辑
          </button>
          <button
            type="button"
            className="text-xs font-medium text-rose-600 hover:text-rose-700"
            onClick={(e) => {
              e.stopPropagation()
              setDeleting(row)
            }}
          >
            删除
          </button>
        </span>
      ),
    },
  ]

  return (
    <div className="space-y-4 animate-fade-up">
      <div>
        <h1 className="text-xl font-semibold text-ink-900">房源管理</h1>
        <p className="mt-1 text-sm text-ink-400">已发布房源的检索、编辑与上下架</p>
      </div>

      <FilterBar
        onSubmit={() => {
          setApplied({ city: cityInput.trim(), query: queryInput.trim() })
          setPage(1)
        }}
      >
        <FilterSelect
          label="状态"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value)
            setPage(1)
          }}
          className="w-36"
        >
          <option value="active">在架</option>
          <option value="unavailable">已下架</option>
          <option value="all">全部</option>
        </FilterSelect>
        <FilterInput label="城市" placeholder="如：上海" value={cityInput} onChange={(e) => setCityInput(e.target.value)} className="w-36" />
        <FilterInput label="搜索" placeholder="标题 / 小区 / 区域 / 来源链接" value={queryInput} onChange={(e) => setQueryInput(e.target.value)} className="w-64" />
        <button type="submit" className="h-9 rounded-full bg-brand-600 px-4 text-sm font-medium text-white transition hover:bg-brand-700">
          查询
        </button>
      </FilterBar>

      <SuccessBar message={flash} />
      {error && <ErrorBar error={error} onRetry={reload} />}
      {actionError && <ErrorBar error={actionError} />}

      <DataTable
        columns={columns}
        rows={listings}
        loading={loading}
        rowKey={(row) => read(row, 'listingId')}
        onRowClick={(row) => setDetailId(read(row, 'listingId'))}
        emptyText="暂无房源"
        footer={
          !loading && listings.length > 0 ? (
            <Pagination
              page={page}
              totalPages={totalPages}
              total={total}
              hasMore={listings.length >= PAGE_SIZE}
              onChange={setPage}
              className="border-t border-ink-100"
            />
          ) : null
        }
      />

      {detailId != null && (
        <ListingDrawer
          listingId={detailId}
          onClose={() => setDetailId(null)}
          onEdit={(record) => {
            setDetailId(null)
            setEditRecord(record)
          }}
        />
      )}

      {editRecord != null && (
        <ListingEditModal
          record={editRecord}
          onClose={() => setEditRecord(null)}
          onSaved={(message) => {
            setEditRecord(null)
            showFlash(message)
            reload()
          }}
        />
      )}

      <Modal
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title="确认删除房源"
        size="sm"
        footer={(
          <>
            <Button variant="secondary" size="sm" onClick={() => setDeleting(null)}>
              取消
            </Button>
            <Button variant="danger" size="sm" loading={deleteBusy} onClick={handleDelete}>
              确认删除
            </Button>
          </>
        )}
      >
        <p className="text-sm leading-6 text-ink-600">
          确定删除房源 <span className="break-all font-medium text-ink-900">{read(deleting, 'listingId', '')}</span>（
          {read(innerListing(deleting ?? {}), 'title') ?? '无标题'}）吗？此操作不可恢复。
        </p>
      </Modal>
    </div>
  )
}

export default AdminListingsPage

import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import Button from '../../components/ui/Button.jsx'
import { SelectField, TextField } from '../../components/ui/Input.jsx'
import Modal from '../../components/ui/Modal.jsx'
import DataTable from '../../features/admin/components/DataTable.jsx'
import { ErrorBar, SuccessBar } from '../../features/admin/components/Notice.jsx'
import { useAsyncData, useFlash } from '../../features/admin/hooks.js'
import { extractItems, formatTime, read, truncate } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

const TONE_OPTIONS = [
  { value: 'info', label: '信息', badge: 'info' },
  { value: 'success', label: '更新完成', badge: 'success' },
  { value: 'warning', label: '重要提醒', badge: 'warning' },
]

function toneBadge(tone) {
  return TONE_OPTIONS.find((option) => option.value === tone)?.badge ?? 'neutral'
}
function toneLabel(tone) {
  return TONE_OPTIONS.find((option) => option.value === tone)?.label ?? tone ?? '—'
}

const EMPTY_FORM = { title: '', body: '', tone: 'info', published: true }

/** 新建/编辑公告弹窗 */
function NotificationModal({ initial, onClose, onSaved }) {
  const isEdit = Boolean(initial?.id)
  const [form, setForm] = useState(() => ({
    title: read(initial, 'title', ''),
    body: read(initial, 'body') ?? read(initial, 'content') ?? '',
    tone: read(initial, 'tone') ?? read(initial, 'level') ?? 'info',
    published: read(initial, 'published') ?? read(initial, 'enabled') ?? true,
  }))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState(null)

  const setField = (key, value) => setForm((prev) => ({ ...prev, [key]: value }))

  const handleSubmit = async () => {
    if (!form.title.trim() || !form.body.trim()) {
      setError(new Error('标题与内容不能为空'))
      return
    }
    setSaving(true)
    setError(null)
    const payload = {
      title: form.title.trim(),
      body: form.body.trim(),
      tone: form.tone,
      published: form.published,
    }
    try {
      if (isEdit) {
        await adminService.updateNotification(initial.id, payload)
      } else {
        await adminService.createNotification(payload)
      }
      onSaved(isEdit ? '公告已更新' : '公告已发布')
    } catch (err) {
      setError(err)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={isEdit ? '编辑公告' : '新建公告'}
      size="lg"
      footer={(
        <>
          <Button variant="secondary" size="sm" onClick={onClose}>
            取消
          </Button>
          <Button size="sm" loading={saving} onClick={handleSubmit}>
            {isEdit ? '保存修改' : '发布公告'}
          </Button>
        </>
      )}
    >
      <div className="space-y-4">
        {error && <ErrorBar error={error} />}
        <TextField
          label="标题"
          maxLength={120}
          value={form.title}
          onChange={(event) => setField('title', event.target.value)}
        />
        <div>
          <label className="mb-1.5 block text-sm font-medium text-ink-700" htmlFor="notification-body">
            内容
          </label>
          <textarea
            id="notification-body"
            rows={5}
            maxLength={1200}
            className="w-full rounded-xl border-0 bg-white px-3.5 py-3 text-sm text-ink-900 shadow-sm ring-1 ring-inset ring-ink-200 placeholder:text-ink-300 focus:ring-2 focus:ring-brand-500"
            value={form.body}
            onChange={(event) => setField('body', event.target.value)}
          />
        </div>
        <div className="flex flex-wrap items-end gap-4">
          <SelectField
            label="级别"
            className="w-44"
            value={form.tone}
            onChange={(event) => setField('tone', event.target.value)}
          >
            {TONE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </SelectField>
          <label className="flex h-11 items-center gap-2 text-sm text-ink-700">
            <input
              type="checkbox"
              className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
              checked={form.published}
              onChange={(event) => setField('published', event.target.checked)}
            />
            发布给用户
          </label>
        </div>
      </div>
    </Modal>
  )
}

/**
 * 公告通知：CRUD 管理。
 */
function AdminNotificationsPage() {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getNotifications(), [])
  const [flash, showFlash] = useFlash()
  const [actionError, setActionError] = useState(null)
  const [editing, setEditing] = useState(null) // null | {} (新建) | notification (编辑)
  const [deleting, setDeleting] = useState(null)
  const [deleteBusy, setDeleteBusy] = useState(false)

  const notifications = extractItems(data)

  const handleDelete = async () => {
    setDeleteBusy(true)
    setActionError(null)
    try {
      await adminService.deleteNotification(read(deleting, 'id'))
      setDeleting(null)
      showFlash('公告已删除')
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
      key: 'title',
      label: '标题',
      render: (row) => <span className="font-medium text-ink-900">{read(row, 'title', '—')}</span>,
    },
    {
      key: 'body',
      label: '内容',
      render: (row) => (
        <span className="text-ink-500">{truncate(read(row, 'body') ?? read(row, 'content'), 48)}</span>
      ),
    },
    {
      key: 'tone',
      label: '级别',
      render: (row) => {
        const tone = read(row, 'tone') ?? read(row, 'level')
        return <Badge tone={toneBadge(tone)}>{toneLabel(tone)}</Badge>
      },
    },
    {
      key: 'published',
      label: '状态',
      render: (row) =>
        (read(row, 'published') ?? read(row, 'enabled')) ? (
          <Badge tone="success">已发布</Badge>
        ) : (
          <Badge tone="neutral">未发布</Badge>
        ),
    },
    { key: 'updatedAt', label: '更新时间', render: (row) => formatTime(read(row, 'updatedAt') ?? read(row, 'createdAt')) },
    {
      key: 'actions',
      label: '操作',
      align: 'right',
      render: (row) => (
        <span className="space-x-3 whitespace-nowrap">
          <button
            type="button"
            className="text-xs font-medium text-brand-600 hover:text-brand-700"
            onClick={() => setEditing(row)}
          >
            编辑
          </button>
          <button
            type="button"
            className="text-xs font-medium text-rose-600 hover:text-rose-700"
            onClick={() => setDeleting(row)}
          >
            删除
          </button>
        </span>
      ),
    },
  ]

  return (
    <div className="space-y-4 animate-fade-up">
      <div className="flex items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-ink-900">公告通知</h1>
          <p className="mt-1 text-sm text-ink-400">面向全部用户的站内公告管理</p>
        </div>
        <Button size="sm" onClick={() => setEditing(EMPTY_FORM)}>
          新建公告
        </Button>
      </div>

      <SuccessBar message={flash} />
      {error && <ErrorBar error={error} onRetry={reload} />}
      {actionError && <ErrorBar error={actionError} />}

      <DataTable
        columns={columns}
        rows={notifications}
        loading={loading}
        rowKey={(row) => read(row, 'id')}
        emptyText="暂无公告"
      />

      {editing !== null && (
        <NotificationModal
          initial={editing}
          onClose={() => setEditing(null)}
          onSaved={(message) => {
            setEditing(null)
            showFlash(message)
            reload()
          }}
        />
      )}

      <Modal
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title="确认删除公告"
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
          确定删除公告 <span className="font-medium text-ink-900">「{read(deleting, 'title', '')}」</span> 吗？此操作不可恢复。
        </p>
      </Modal>
    </div>
  )
}

export default AdminNotificationsPage

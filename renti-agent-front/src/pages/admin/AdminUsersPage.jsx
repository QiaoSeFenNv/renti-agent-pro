import { useState } from 'react'

import Badge from '../../components/ui/Badge.jsx'
import Button from '../../components/ui/Button.jsx'
import { TextField } from '../../components/ui/Input.jsx'
import Modal from '../../components/ui/Modal.jsx'
import { LoadingBlock } from '../../components/ui/Feedback.jsx'
import DataTable from '../../features/admin/components/DataTable.jsx'
import Drawer from '../../features/admin/components/Drawer.jsx'
import { ErrorBar, SuccessBar } from '../../features/admin/components/Notice.jsx'
import { useAsyncData, useFlash } from '../../features/admin/hooks.js'
import { extractItems, formatTime, pick, read, toJson } from '../../features/admin/utils.js'
import { adminService } from '../../services/adminService.js'

/** 详情抽屉内的分区标题 */
function SectionTitle({ children }) {
  return <h4 className="text-xs font-semibold uppercase tracking-wide text-ink-400">{children}</h4>
}

function InfoRow({ label, children }) {
  return (
    <div className="flex items-start justify-between gap-3 py-1.5 text-sm">
      <span className="shrink-0 text-ink-400">{label}</span>
      <span className="min-w-0 break-all text-right text-ink-800">{children ?? '—'}</span>
    </div>
  )
}

/** JSON 文本编辑块：textarea + 保存按钮 */
function JsonEditor({ label, value, onSave, saving, hint }) {
  const [text, setText] = useState(value)
  const [error, setError] = useState('')

  const handleSave = () => {
    let parsed
    try {
      parsed = JSON.parse(text)
    } catch {
      setError('JSON 格式不合法，请检查后重试')
      return
    }
    setError('')
    onSave(parsed)
  }

  return (
    <div className="space-y-2">
      <textarea
        className="h-44 w-full rounded-xl border-0 bg-black/50 px-3.5 py-3 font-mono text-xs leading-5 text-ink-800 ring-1 ring-inset ring-white/10 scrollbar-thin focus:ring-2 focus:ring-brand-500/80"
        value={text}
        onChange={(event) => setText(event.target.value)}
        aria-label={label}
        spellCheck="false"
      />
      {hint && <p className="text-xs text-ink-400">{hint}</p>}
      {error && <p className="text-xs text-rose-700">{error}</p>}
      <Button size="sm" onClick={handleSave} loading={saving}>
        保存{label}
      </Button>
    </div>
  )
}

/** 用户详情抽屉 */
function UserDrawer({ userId, onClose, onChanged }) {
  const { loading, error, data, reload } = useAsyncData(
    () => adminService.getUserDetail(userId),
    [userId],
  )
  const [flash, showFlash] = useFlash()
  const [actionError, setActionError] = useState(null)
  const [savingKey, setSavingKey] = useState('')

  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleteConfirmText, setDeleteConfirmText] = useState('')
  const [deleting, setDeleting] = useState(false)

  const user = pick(data, 'user') ?? {}
  const config = pick(data, 'config') ?? {}
  const workspace = read(user, 'workspace') ?? {}
  const settings = read(workspace, 'settings') ?? {}
  const email = read(user, 'email', '')

  const runAction = async (key, action, successText) => {
    setSavingKey(key)
    setActionError(null)
    try {
      await action()
      showFlash(successText)
      await reload()
      onChanged?.()
      return true
    } catch (err) {
      setActionError(err)
      return false
    } finally {
      setSavingKey('')
    }
  }

  const handleResetPassword = () => {
    if (newPassword.length < 10) {
      setActionError(new Error('新密码至少 10 位，建议包含字母和数字'))
      return
    }
    if (newPassword !== confirmPassword) {
      setActionError(new Error('两次输入的密码不一致'))
      return
    }
    runAction('password', () => adminService.resetUserPassword(userId, { password: newPassword }), '密码已重置').then(
      (ok) => {
        if (ok) {
          setNewPassword('')
          setConfirmPassword('')
        }
      },
    )
  }

  const handleDelete = async () => {
    setDeleting(true)
    setActionError(null)
    try {
      await adminService.deleteUser(userId)
      setDeleteOpen(false)
      onChanged?.()
      onClose()
    } catch (err) {
      setActionError(err)
      setDeleteOpen(false)
    } finally {
      setDeleting(false)
    }
  }

  return (
    <Drawer open onClose={onClose} title="用户详情" subtitle={email || `#${userId}`} size="md">
      {loading && <LoadingBlock text="正在加载用户详情…" />}
      {error && <ErrorBar error={error} onRetry={reload} />}

      {!loading && !error && (
        <div className="space-y-6">
          <SuccessBar message={flash} />
          <ErrorBar error={actionError} />

          <section className="space-y-1">
            <SectionTitle>基本信息</SectionTitle>
            <div className="divide-y divide-white/[0.06] rounded-xl px-3 ring-1 ring-white/[0.06]">
              <InfoRow label="ID">{read(user, 'id', userId)}</InfoRow>
              <InfoRow label="邮箱">{email}</InfoRow>
              <InfoRow label="昵称">{read(user, 'displayName') ?? read(user, 'nickname')}</InfoRow>
              <InfoRow label="邮箱验证">
                {read(user, 'emailVerified') ? (
                  <Badge tone="success">已验证</Badge>
                ) : (
                  <Badge tone="warning">未验证</Badge>
                )}
              </InfoRow>
              <InfoRow label="注册时间">{formatTime(read(user, 'createdAt'))}</InfoRow>
              <InfoRow label="更新时间">{formatTime(read(user, 'updatedAt'))}</InfoRow>
              <InfoRow label="收藏 / 历史">
                {`${read(workspace, 'favoriteCount', 0)} / ${read(workspace, 'historyCount', 0)}`}
              </InfoRow>
              <InfoRow label="模型配置">
                {read(workspace, 'usesPlatformDefault', read(config, 'usesPlatformDefault', true)) ? (
                  <Badge tone="neutral">平台默认</Badge>
                ) : (
                  <Badge tone="brand">用户专属</Badge>
                )}
              </InfoRow>
            </div>
          </section>

          <section className="space-y-2">
            <SectionTitle>工作台设置（JSON）</SectionTitle>
            <JsonEditor
              key={`settings-${toJson(settings)}`}
              label="设置"
              value={toJson(settings) || '{}'}
              saving={savingKey === 'settings'}
              hint="对应 PUT /users/{id}/settings 的 settings 字段"
              onSave={(parsed) =>
                runAction('settings', () => adminService.updateUserSettings(userId, { settings: parsed }), '设置已保存')
              }
            />
          </section>

          <section className="space-y-2">
            <SectionTitle>专属模型配置（JSON）</SectionTitle>
            <JsonEditor
              key={`config-${toJson(config)}`}
              label="配置"
              value={toJson({ modelOptions: read(config, 'modelOptions', []) }) || '{}'}
              saving={savingKey === 'config'}
              hint="编辑 modelOptions 列表；保存后该用户使用专属配置"
              onSave={(parsed) =>
                runAction('config', () => adminService.updateUserConfig(userId, parsed), '配置已保存')
              }
            />
            <Button
              size="sm"
              variant="secondary"
              loading={savingKey === 'resetConfig'}
              onClick={() =>
                runAction('resetConfig', () => adminService.resetUserConfig(userId), '已恢复平台默认配置')
              }
            >
              重置为平台默认
            </Button>
          </section>

          <section className="space-y-2">
            <SectionTitle>重置密码</SectionTitle>
            <TextField
              label="新密码"
              type="password"
              placeholder="至少 10 位，包含字母和数字"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
            />
            <TextField
              label="确认新密码"
              type="password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
            />
            <Button size="sm" variant="secondary" loading={savingKey === 'password'} onClick={handleResetPassword}>
              强制重置密码
            </Button>
          </section>

          <section className="space-y-2 rounded-xl bg-rose-50/60 p-4 ring-1 ring-rose-100">
            <SectionTitle>危险操作</SectionTitle>
            <p className="text-xs leading-5 text-ink-500">删除用户将同时清除其收藏、历史与设置，不可恢复。</p>
            <Button size="sm" variant="danger" onClick={() => setDeleteOpen(true)}>
              删除用户
            </Button>
          </section>
        </div>
      )}

      <Modal
        open={deleteOpen}
        onClose={() => setDeleteOpen(false)}
        title="确认删除用户"
        size="sm"
        footer={(
          <>
            <Button variant="secondary" size="sm" onClick={() => setDeleteOpen(false)}>
              取消
            </Button>
            <Button
              variant="danger"
              size="sm"
              loading={deleting}
              disabled={deleteConfirmText.trim().toLowerCase() !== String(email).toLowerCase() || !email}
              onClick={handleDelete}
            >
              确认删除
            </Button>
          </>
        )}
      >
        <p className="text-sm leading-6 text-ink-600">
          此操作不可恢复。请输入该用户邮箱 <span className="font-medium text-ink-900">{email}</span> 以确认删除：
        </p>
        <TextField
          className="mt-3"
          placeholder="输入用户邮箱"
          value={deleteConfirmText}
          onChange={(event) => setDeleteConfirmText(event.target.value)}
        />
      </Modal>
    </Drawer>
  )
}

/**
 * 用户管理：列表 + 详情抽屉。
 */
function AdminUsersPage() {
  const { loading, error, data, reload } = useAsyncData(() => adminService.getUsers(200), [])
  const [selectedId, setSelectedId] = useState(null)

  const users = extractItems(data)

  const columns = [
    { key: 'id', label: 'ID', className: 'w-16 tabular-nums text-ink-400' },
    { key: 'email', label: '邮箱', render: (row) => <span className="font-medium text-ink-900">{read(row, 'email', '—')}</span> },
    { key: 'displayName', label: '昵称', render: (row) => read(row, 'displayName') ?? read(row, 'nickname') ?? '—' },
    {
      key: 'emailVerified',
      label: '验证状态',
      render: (row) =>
        read(row, 'emailVerified') ? <Badge tone="success">已验证</Badge> : <Badge tone="warning">未验证</Badge>,
    },
    { key: 'createdAt', label: '注册时间', render: (row) => formatTime(read(row, 'createdAt')) },
    {
      key: 'actions',
      label: '操作',
      align: 'right',
      render: (row) => (
        <button
          type="button"
          className="text-xs font-medium text-brand-300 hover:text-brand-200"
          onClick={(event) => {
            event.stopPropagation()
            setSelectedId(read(row, 'id'))
          }}
        >
          详情
        </button>
      ),
    },
  ]

  return (
    <div className="space-y-4 animate-fade-up">
      <div className="flex items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-ink-900">用户管理</h1>
          <p className="mt-1 text-sm text-ink-400">
            {typeof pick(data, 'total') === 'number' ? `共 ${pick(data, 'total')} 位用户` : '查看与管理注册用户'}
          </p>
        </div>
      </div>

      {error && <ErrorBar error={error} onRetry={reload} />}

      <DataTable
        columns={columns}
        rows={users}
        loading={loading}
        rowKey={(row) => read(row, 'id')}
        onRowClick={(row) => setSelectedId(read(row, 'id'))}
        emptyText="暂无用户"
      />

      {selectedId != null && (
        <UserDrawer userId={selectedId} onClose={() => setSelectedId(null)} onChanged={reload} />
      )}
    </div>
  )
}

export default AdminUsersPage

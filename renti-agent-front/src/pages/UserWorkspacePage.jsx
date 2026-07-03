import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

import Badge from '../components/ui/Badge.jsx'
import Button from '../components/ui/Button.jsx'
import Card, { CardBody, CardHeader } from '../components/ui/Card.jsx'
import { EmptyState, LoadingBlock } from '../components/ui/Feedback.jsx'
import TextField, { SelectField } from '../components/ui/Input.jsx'
import Modal from '../components/ui/Modal.jsx'
import SiteLayout from '../layouts/SiteLayout.jsx'
import { listingService } from '../services/searchService.js'
import { userService } from '../services/userService.js'

const TABS = [
  { key: 'favorites', label: '收藏' },
  { key: 'history', label: '搜索历史' },
  { key: 'imports', label: '导入房源' },
  { key: 'notifications', label: '通知' },
  { key: 'settings', label: '账号设置' },
]

const formatTime = (value) => {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString('zh-CN', { hour12: false })
}

/* ---------------- 收藏 ---------------- */

function FavoritesTab() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [favorites, setFavorites] = useState([])
  const [pendingRemove, setPendingRemove] = useState(null)
  const [removing, setRemoving] = useState(false)
  const [removeError, setRemoveError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await userService.getFavorites()
      setFavorites(Array.isArray(data?.favorites) ? data.favorites : [])
    } catch (err) {
      setError(err.message || '收藏列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const confirmRemove = async () => {
    if (!pendingRemove) return
    setRemoving(true)
    setRemoveError('')
    try {
      await userService.removeFavorite(pendingRemove.listingId)
      setFavorites((list) => list.filter((item) => item.listingId !== pendingRemove.listingId))
      setPendingRemove(null)
    } catch (err) {
      setRemoveError(err.message || '取消收藏失败')
    } finally {
      setRemoving(false)
    }
  }

  if (loading) return <LoadingBlock text="正在加载收藏…" />
  if (error) {
    return (
      <EmptyState
        icon="⚠️"
        title="收藏加载失败"
        description={error}
        action={<Button variant="secondary" size="sm" onClick={load}>重试</Button>}
      />
    )
  }
  if (favorites.length === 0) {
    return (
      <EmptyState
        icon="⭐"
        title="还没有收藏的房源"
        description="在城市工作台的推荐结果里点击收藏，会同步到这里。"
        action={<Button size="sm" onClick={() => navigate('/cities')}>去找房</Button>}
      />
    )
  }

  return (
    <>
      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {favorites.map((item) => {
          const listing = item.listing || {}
          const image = listingService.proxyImageUrl(listing.image)
          const price = listing.price || listing.rent_price
          const location =
            listing.location ||
            [listing.district, listing.business_area, listing.community].filter(Boolean).join(' · ')
          return (
            <li key={item.listingId}>
              <Card hover className="flex h-full flex-col overflow-hidden">
                <button
                  type="button"
                  className="text-left"
                  onClick={() => navigate(`/property/${encodeURIComponent(item.listingId)}`)}
                  aria-label={`查看房源详情：${listing.title || item.listingId}`}
                >
                  <div className="h-36 w-full bg-ink-100">
                    {image ? (
                      <img
                        src={image}
                        alt={listing.title || '房源图片'}
                        className="h-full w-full object-cover"
                        loading="lazy"
                      />
                    ) : (
                      <div className="flex h-full items-center justify-center text-2xl text-ink-300" aria-hidden="true">
                        🏠
                      </div>
                    )}
                  </div>
                  <div className="px-4 pt-3">
                    <p className="line-clamp-1 text-sm font-semibold text-ink-900">
                      {listing.title || '未命名房源'}
                    </p>
                    <p className="mt-1 line-clamp-1 text-xs text-ink-500">{location || '位置信息待补充'}</p>
                    {price && <p className="mt-1.5 text-sm font-semibold text-brand-600">{price}</p>}
                  </div>
                </button>
                <div className="mt-auto flex items-center justify-between px-4 pb-3 pt-2">
                  <span className="text-xs text-ink-400">收藏于 {formatTime(item.createdAt)}</span>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => {
                      setRemoveError('')
                      setPendingRemove(item)
                    }}
                    aria-label={`取消收藏：${listing.title || item.listingId}`}
                  >
                    取消收藏
                  </Button>
                </div>
              </Card>
            </li>
          )
        })}
      </ul>

      <Modal
        open={Boolean(pendingRemove)}
        onClose={() => setPendingRemove(null)}
        title="取消收藏"
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setPendingRemove(null)}>
              保留
            </Button>
            <Button variant="danger" loading={removing} onClick={confirmRemove}>
              确认取消
            </Button>
          </>
        }
      >
        <p className="text-sm text-ink-600">
          确定取消收藏「{pendingRemove?.listing?.title || pendingRemove?.listingId}」吗？取消后需要重新在工作台收藏。
        </p>
        {removeError && <p className="mt-2 text-sm text-rose-600">{removeError}</p>}
      </Modal>
    </>
  )
}

/* ---------------- 搜索历史 ---------------- */

function HistoryTab() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [history, setHistory] = useState([])
  const [clearOpen, setClearOpen] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [clearError, setClearError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await userService.getHistory()
      setHistory(Array.isArray(data?.history) ? data.history : [])
    } catch (err) {
      setError(err.message || '搜索历史加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const confirmClear = async () => {
    setClearing(true)
    setClearError('')
    try {
      await userService.clearHistory()
      setHistory([])
      setClearOpen(false)
    } catch (err) {
      setClearError(err.message || '清空失败')
    } finally {
      setClearing(false)
    }
  }

  const openHistory = (entry) => {
    const city = localStorage.getItem('renti.lastCity') || '上海'
    navigate(`/city/${encodeURIComponent(city)}?q=${encodeURIComponent(entry.queryText || '')}`)
  }

  if (loading) return <LoadingBlock text="正在加载搜索历史…" />
  if (error) {
    return (
      <EmptyState
        icon="⚠️"
        title="搜索历史加载失败"
        description={error}
        action={<Button variant="secondary" size="sm" onClick={load}>重试</Button>}
      />
    )
  }
  if (history.length === 0) {
    return <EmptyState icon="🕘" title="暂无搜索历史" description="在城市工作台完成一次搜索后会记录在这里。" />
  }

  return (
    <>
      <div className="mb-4 flex justify-end">
        <Button variant="secondary" size="sm" onClick={() => setClearOpen(true)}>
          清空历史
        </Button>
      </div>
      <ul className="space-y-3">
        {history.map((entry) => (
          <li key={entry.id}>
            <button
              type="button"
              onClick={() => openHistory(entry)}
              aria-label={`重新搜索：${entry.queryText || entry.centerLabel || '历史查询'}`}
              className="w-full rounded-2xl bg-white p-4 text-left shadow-card ring-1 ring-ink-100/60 transition hover:-translate-y-0.5 hover:shadow-float"
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="text-sm font-semibold text-ink-900">
                  {entry.queryText || entry.centerLabel || '历史查询'}
                </p>
                <span className="text-xs text-ink-400">{formatTime(entry.createdAt)}</span>
              </div>
              <p className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-500">
                {entry.centerLabel && <span>📍 {entry.centerLabel}</span>}
                <span>结果 {entry.resultCount ?? 0} 条</span>
              </p>
            </button>
          </li>
        ))}
      </ul>

      <Modal
        open={clearOpen}
        onClose={() => setClearOpen(false)}
        title="清空搜索历史"
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setClearOpen(false)}>
              取消
            </Button>
            <Button variant="danger" loading={clearing} onClick={confirmClear}>
              确认清空
            </Button>
          </>
        }
      >
        <p className="text-sm text-ink-600">将删除全部 {history.length} 条搜索历史，操作不可恢复。</p>
        {clearError && <p className="mt-2 text-sm text-rose-600">{clearError}</p>}
      </Modal>
    </>
  )
}

/* ---------------- 导入房源 ---------------- */

function ImportsTab() {
  const [city, setCity] = useState(localStorage.getItem('renti.lastCity') || '上海')
  const [cityInput, setCityInput] = useState(localStorage.getItem('renti.lastCity') || '上海')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [listings, setListings] = useState([])

  const [jsonText, setJsonText] = useState('')
  const [importState, setImportState] = useState({ status: 'idle', message: '' })
  const [clearOpen, setClearOpen] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [clearError, setClearError] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await userService.getImportedListings(city)
      setListings(Array.isArray(data?.listings) ? data.listings : [])
    } catch (err) {
      setError(err.message || '导入房源加载失败')
    } finally {
      setLoading(false)
    }
  }, [city])

  useEffect(() => {
    load()
  }, [load])

  const handleImport = async (event) => {
    event.preventDefault()
    let parsed
    try {
      parsed = JSON.parse(jsonText)
    } catch {
      setImportState({ status: 'error', message: 'JSON 格式不正确，请检查后重试。' })
      return
    }
    setImportState({ status: 'loading', message: '' })
    try {
      const items = Array.isArray(parsed) ? parsed : [parsed]
      for (const listing of items) {
        // eslint-disable-next-line no-await-in-loop
        await userService.saveImportedListing({ city, listing })
      }
      setImportState({ status: 'success', message: `成功导入 ${items.length} 条房源。` })
      setJsonText('')
      load()
    } catch (err) {
      setImportState({ status: 'error', message: err.message || '导入失败，请稍后重试。' })
    }
  }

  const confirmClear = async () => {
    setClearing(true)
    setClearError('')
    try {
      await userService.clearImportedListings(city)
      setListings([])
      setClearOpen(false)
    } catch (err) {
      setClearError(err.message || '清空失败')
    } finally {
      setClearing(false)
    }
  }

  return (
    <div className="space-y-6">
      {/* 城市选择 */}
      <Card>
        <CardBody className="flex flex-wrap items-end gap-3">
          <TextField
            label="城市"
            value={cityInput}
            onChange={(event) => setCityInput(event.target.value)}
            placeholder="如：上海"
            className="w-48"
          />
          <Button
            variant="secondary"
            onClick={() => setCity(cityInput.trim() || '上海')}
            aria-label="切换城市"
          >
            切换城市
          </Button>
          <p className="text-xs text-ink-400">导入的房源按城市隔离，当前查看：{city}</p>
        </CardBody>
      </Card>

      {/* 导入表单 */}
      <Card>
        <CardHeader
          title="粘贴 JSON 导入"
          description="支持单个对象或数组，字段建议包含 id / title / price / location / image。"
        />
        <CardBody>
          <form onSubmit={handleImport} className="space-y-3">
            <label htmlFor="import-json" className="sr-only">
              房源 JSON
            </label>
            <textarea
              id="import-json"
              value={jsonText}
              onChange={(event) => setJsonText(event.target.value)}
              rows={6}
              placeholder='{"id": "demo-1", "title": "两室一厅", "price": "6500 元/月", "location": "徐汇区"}'
              className="w-full rounded-xl border-0 bg-white px-3.5 py-3 font-mono text-xs text-ink-900 shadow-sm ring-1 ring-inset ring-ink-200 transition placeholder:text-ink-300 focus:ring-2 focus:ring-brand-500"
            />
            {importState.message && (
              <p
                className={[
                  'text-sm',
                  importState.status === 'success' ? 'text-emerald-600' : 'text-rose-600',
                ].join(' ')}
                role="status"
              >
                {importState.message}
              </p>
            )}
            <div className="flex justify-end">
              <Button type="submit" loading={importState.status === 'loading'} disabled={!jsonText.trim()}>
                导入房源
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>

      {/* 列表 */}
      <Card>
        <CardHeader
          title={`已导入房源（${listings.length}）`}
          actions={
            listings.length > 0 && (
              <Button variant="secondary" size="sm" onClick={() => setClearOpen(true)}>
                清空本城市
              </Button>
            )
          }
        />
        <CardBody>
          {loading ? (
            <LoadingBlock text="正在加载导入房源…" className="py-10" />
          ) : error ? (
            <EmptyState
              icon="⚠️"
              title="导入房源加载失败"
              description={error}
              className="py-10"
              action={<Button variant="secondary" size="sm" onClick={load}>重试</Button>}
            />
          ) : listings.length === 0 ? (
            <EmptyState
              icon="📥"
              title="该城市暂无导入房源"
              description="通过上方 JSON 表单导入自有候选房源，即可在 user_import 模式下分析。"
              className="py-10"
            />
          ) : (
            <ul className="divide-y divide-ink-100">
              {listings.map((item) => {
                const listing = item.listing || {}
                return (
                  <li key={item.listingId} className="flex items-center justify-between gap-3 py-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-ink-900">
                        {listing.title || item.listingId}
                      </p>
                      <p className="mt-0.5 truncate text-xs text-ink-500">
                        {[listing.price, listing.location].filter(Boolean).join(' · ') || '—'}
                      </p>
                    </div>
                    <span className="shrink-0 text-xs text-ink-400">{formatTime(item.createdAt)}</span>
                  </li>
                )
              })}
            </ul>
          )}
        </CardBody>
      </Card>

      <Modal
        open={clearOpen}
        onClose={() => setClearOpen(false)}
        title="清空导入房源"
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setClearOpen(false)}>
              取消
            </Button>
            <Button variant="danger" loading={clearing} onClick={confirmClear}>
              确认清空
            </Button>
          </>
        }
      >
        <p className="text-sm text-ink-600">
          将删除「{city}」下全部 {listings.length} 条导入房源，操作不可恢复。
        </p>
        {clearError && <p className="mt-2 text-sm text-rose-600">{clearError}</p>}
      </Modal>
    </div>
  )
}

/* ---------------- 通知 ---------------- */

function NotificationsTab() {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [notifications, setNotifications] = useState([])
  const [actionError, setActionError] = useState('')
  const [markingAll, setMarkingAll] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await userService.getNotifications()
      setNotifications(Array.isArray(data?.notifications) ? data.notifications : [])
    } catch (err) {
      setError(err.message || '通知加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const markRead = async (id) => {
    setActionError('')
    try {
      await userService.readNotification(id)
      setNotifications((list) =>
        list.map((item) => (item.id === id ? { ...item, isRead: true } : item)),
      )
    } catch (err) {
      setActionError(err.message || '标记已读失败')
    }
  }

  const markAllRead = async () => {
    setMarkingAll(true)
    setActionError('')
    try {
      await userService.readAllNotifications()
      setNotifications((list) => list.map((item) => ({ ...item, isRead: true })))
    } catch (err) {
      setActionError(err.message || '全部已读失败')
    } finally {
      setMarkingAll(false)
    }
  }

  if (loading) return <LoadingBlock text="正在加载通知…" />
  if (error) {
    return (
      <EmptyState
        icon="⚠️"
        title="通知加载失败"
        description={error}
        action={<Button variant="secondary" size="sm" onClick={load}>重试</Button>}
      />
    )
  }
  if (notifications.length === 0) {
    return <EmptyState icon="🔔" title="暂无通知" description="平台公告与城市上线消息会出现在这里。" />
  }

  const unread = notifications.filter((item) => !item.isRead).length
  const toneMap = { info: 'info', success: 'success', warning: 'warning' }

  return (
    <>
      <div className="mb-4 flex items-center justify-between">
        <p className="text-sm text-ink-500">未读 {unread} 条</p>
        <Button variant="secondary" size="sm" loading={markingAll} disabled={unread === 0} onClick={markAllRead}>
          全部已读
        </Button>
      </div>
      {actionError && <p className="mb-3 text-sm text-rose-600">{actionError}</p>}
      <ul className="space-y-3">
        {notifications.map((item) => (
          <li key={item.id}>
            <div
              className={[
                'rounded-2xl bg-white p-4 shadow-card ring-1',
                item.isRead ? 'ring-ink-100/60 opacity-75' : 'ring-brand-200',
              ].join(' ')}
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  {!item.isRead && <span className="h-2 w-2 rounded-full bg-brand-500" aria-hidden="true" />}
                  <p className="text-sm font-semibold text-ink-900">{item.title}</p>
                  {item.tone && <Badge tone={toneMap[item.tone] || 'neutral'}>{item.tone}</Badge>}
                </div>
                <span className="text-xs text-ink-400">{formatTime(item.createdAt)}</span>
              </div>
              {item.body && <p className="mt-2 text-sm leading-6 text-ink-600">{item.body}</p>}
              {!item.isRead && (
                <div className="mt-2 flex justify-end">
                  <Button variant="ghost" size="sm" onClick={() => markRead(item.id)} aria-label={`标记已读：${item.title}`}>
                    标记已读
                  </Button>
                </div>
              )}
            </div>
          </li>
        ))}
      </ul>
    </>
  )
}

/* ---------------- 账号设置 ---------------- */

const SORT_OPTIONS = [
  { value: 'score_desc', label: '综合评分优先' },
  { value: 'price_asc', label: '价格从低到高' },
]
const MAP_STYLES = [
  { value: 'normal', label: '标准' },
  { value: 'light', label: '浅色' },
]
const ANALYSIS_FOCUS = [
  { value: 'balanced', label: '均衡' },
  { value: 'commute', label: '通勤优先' },
  { value: 'price', label: '价格优先' },
  { value: 'amenities', label: '配套优先' },
]

function SettingsTab() {
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [settings, setSettings] = useState(null)
  const [modelOptions, setModelOptions] = useState([])
  const [pageSizeOptions, setPageSizeOptions] = useState([5, 10])
  const [saveState, setSaveState] = useState({ status: 'idle', message: '' })

  const [pwd, setPwd] = useState({ currentPassword: '', newPassword: '', confirmPassword: '' })
  const [pwdErrors, setPwdErrors] = useState({})
  const [pwdState, setPwdState] = useState({ status: 'idle', message: '' })

  const [clearOpen, setClearOpen] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [clearState, setClearState] = useState({ status: 'idle', message: '' })

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const data = await userService.getSettings()
      setSettings(data?.settings || {})
      setModelOptions(Array.isArray(data?.modelOptions) ? data.modelOptions : [])
      if (Array.isArray(data?.listingPageSizeOptions) && data.listingPageSizeOptions.length > 0) {
        setPageSizeOptions(data.listingPageSizeOptions)
      }
    } catch (err) {
      setError(err.message || '设置加载失败')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const updateField = (key, value) => {
    setSettings((current) => ({ ...current, [key]: value }))
  }

  const handleSave = async (event) => {
    event.preventDefault()
    setSaveState({ status: 'loading', message: '' })
    try {
      await userService.updateSettings(settings)
      setSaveState({ status: 'success', message: '设置已保存。' })
    } catch (err) {
      setSaveState({ status: 'error', message: err.message || '保存失败，请稍后重试。' })
    }
  }

  const handleChangePassword = async (event) => {
    event.preventDefault()
    const errors = {}
    if (!pwd.currentPassword) errors.currentPassword = '请输入当前密码'
    if (pwd.newPassword.length < 10) {
      errors.newPassword = '新密码至少 10 位'
    } else if (!/[A-Za-z]/.test(pwd.newPassword) || !/\d/.test(pwd.newPassword)) {
      errors.newPassword = '新密码需同时包含字母和数字'
    }
    if (pwd.confirmPassword !== pwd.newPassword) errors.confirmPassword = '两次输入的新密码不一致'
    setPwdErrors(errors)
    if (Object.keys(errors).length > 0) return

    setPwdState({ status: 'loading', message: '' })
    try {
      const data = await userService.changePassword(pwd)
      if (data && data.ok === false) {
        setPwdErrors(data.fieldErrors || {})
        setPwdState({ status: 'error', message: data.summary || '修改失败，请稍后重试。' })
        return
      }
      setPwd({ currentPassword: '', newPassword: '', confirmPassword: '' })
      setPwdState({ status: 'success', message: '密码已更新。' })
    } catch (err) {
      setPwdErrors(err?.payload?.fieldErrors || {})
      setPwdState({ status: 'error', message: err.message || '修改失败，请稍后重试。' })
    }
  }

  const confirmClearPreferences = async () => {
    setClearing(true)
    try {
      await userService.clearPreferences()
      setClearState({ status: 'success', message: '偏好已清除。' })
      setClearOpen(false)
    } catch (err) {
      setClearState({ status: 'error', message: err.message || '清除失败，请稍后重试。' })
      setClearOpen(false)
    } finally {
      setClearing(false)
    }
  }

  if (loading) return <LoadingBlock text="正在加载设置…" />
  if (error) {
    return (
      <EmptyState
        icon="⚠️"
        title="设置加载失败"
        description={error}
        action={<Button variant="secondary" size="sm" onClick={load}>重试</Button>}
      />
    )
  }

  return (
    <div className="space-y-6">
      {/* 工作台偏好 */}
      <Card>
        <CardHeader title="工作台偏好" description="影响城市工作台的默认搜索与展示行为。" />
        <CardBody>
          <form onSubmit={handleSave} className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <TextField
                label="默认搜索半径（米）"
                type="number"
                min={100}
                step={100}
                value={settings?.defaultRadiusMeters ?? 300}
                onChange={(event) => updateField('defaultRadiusMeters', Number(event.target.value))}
              />
              <SelectField
                label="默认排序"
                value={settings?.defaultSort ?? 'score_desc'}
                onChange={(event) => updateField('defaultSort', event.target.value)}
              >
                {SORT_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </SelectField>
              <SelectField
                label="地图样式"
                value={settings?.mapStyle ?? 'normal'}
                onChange={(event) => updateField('mapStyle', event.target.value)}
              >
                {MAP_STYLES.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </SelectField>
              <SelectField
                label="分析侧重"
                value={settings?.analysisFocus ?? 'balanced'}
                onChange={(event) => updateField('analysisFocus', event.target.value)}
              >
                {ANALYSIS_FOCUS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </SelectField>
              <SelectField
                label="每页房源数"
                value={settings?.listingPageSize ?? 5}
                onChange={(event) => updateField('listingPageSize', Number(event.target.value))}
              >
                {pageSizeOptions.map((size) => (
                  <option key={size} value={size}>
                    {size} 条 / 页
                  </option>
                ))}
              </SelectField>
              <SelectField
                label="模型档位"
                value={settings?.modelProfile ?? 'balanced'}
                onChange={(event) => updateField('modelProfile', event.target.value)}
              >
                {(modelOptions.length > 0
                  ? modelOptions
                  : [
                      { value: 'fast', label: '快速' },
                      { value: 'balanced', label: '均衡' },
                      { value: 'deep', label: '深度' },
                    ]
                ).map((option) => (
                  <option key={option.value} value={option.value} disabled={option.enabled === false}>
                    {option.label}
                  </option>
                ))}
              </SelectField>
            </div>

            <div className="flex flex-wrap gap-6">
              <label className="flex items-center gap-2 text-sm text-ink-700">
                <input
                  type="checkbox"
                  checked={Boolean(settings?.autoOpenResults ?? true)}
                  onChange={(event) => updateField('autoOpenResults', event.target.checked)}
                  className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                />
                搜索后自动展开结果面板
              </label>
              <label className="flex items-center gap-2 text-sm text-ink-700">
                <input
                  type="checkbox"
                  checked={Boolean(settings?.saveSearchHistory ?? true)}
                  onChange={(event) => updateField('saveSearchHistory', event.target.checked)}
                  className="h-4 w-4 rounded border-ink-300 text-brand-600 focus:ring-brand-500"
                />
                保存搜索历史
              </label>
            </div>

            {saveState.message && (
              <p
                className={[
                  'text-sm',
                  saveState.status === 'success' ? 'text-emerald-600' : 'text-rose-600',
                ].join(' ')}
                role="status"
              >
                {saveState.message}
              </p>
            )}

            <div className="flex justify-end">
              <Button type="submit" loading={saveState.status === 'loading'}>
                保存设置
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>

      {/* 修改密码 */}
      <Card>
        <CardHeader title="修改密码" description="新密码至少 10 位，需包含字母和数字。" />
        <CardBody>
          <form onSubmit={handleChangePassword} className="max-w-md space-y-4">
            <TextField
              label="当前密码"
              type="password"
              autoComplete="current-password"
              value={pwd.currentPassword}
              onChange={(event) => setPwd((current) => ({ ...current, currentPassword: event.target.value }))}
              error={pwdErrors.currentPassword}
            />
            <TextField
              label="新密码"
              type="password"
              autoComplete="new-password"
              value={pwd.newPassword}
              onChange={(event) => setPwd((current) => ({ ...current, newPassword: event.target.value }))}
              error={pwdErrors.newPassword}
            />
            <TextField
              label="确认新密码"
              type="password"
              autoComplete="new-password"
              value={pwd.confirmPassword}
              onChange={(event) => setPwd((current) => ({ ...current, confirmPassword: event.target.value }))}
              error={pwdErrors.confirmPassword}
            />
            {pwdState.message && (
              <p
                className={[
                  'text-sm',
                  pwdState.status === 'success' ? 'text-emerald-600' : 'text-rose-600',
                ].join(' ')}
                role="status"
              >
                {pwdState.message}
              </p>
            )}
            <div className="flex justify-end">
              <Button type="submit" loading={pwdState.status === 'loading'}>
                更新密码
              </Button>
            </div>
          </form>
        </CardBody>
      </Card>

      {/* 清除偏好 */}
      <Card>
        <CardHeader
          title="清除个性化偏好"
          description="删除预算、通勤目标、常用区域等个性化偏好，不影响收藏与历史。"
          actions={
            <Button variant="secondary" size="sm" onClick={() => setClearOpen(true)}>
              清除偏好
            </Button>
          }
        />
        {clearState.message && (
          <CardBody className="pt-0">
            <p
              className={[
                'text-sm',
                clearState.status === 'success' ? 'text-emerald-600' : 'text-rose-600',
              ].join(' ')}
              role="status"
            >
              {clearState.message}
            </p>
          </CardBody>
        )}
      </Card>

      <Modal
        open={clearOpen}
        onClose={() => setClearOpen(false)}
        title="清除个性化偏好"
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setClearOpen(false)}>
              取消
            </Button>
            <Button variant="danger" loading={clearing} onClick={confirmClearPreferences}>
              确认清除
            </Button>
          </>
        }
      >
        <p className="text-sm text-ink-600">将清除账号内保存的个性化偏好（预算 / 通勤目标 / 常用区域），操作不可恢复。</p>
      </Modal>
    </div>
  )
}

/* ---------------- 页面主体 ---------------- */

/**
 * 我的工作台：收藏 / 搜索历史 / 导入房源 / 通知 / 账号设置 Tab 布局。
 */
function UserWorkspacePage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const tabParam = searchParams.get('tab')
  const activeTab = TABS.some((tab) => tab.key === tabParam) ? tabParam : 'favorites'

  const selectTab = (key) => {
    setSearchParams(key === 'favorites' ? {} : { tab: key }, { replace: true })
  }

  return (
    <SiteLayout>
      <div className="mx-auto max-w-7xl px-4 py-10 sm:px-6">
        <div className="animate-fade-up">
          <h1 className="text-3xl font-semibold tracking-tight text-ink-900">我的工作台</h1>
          <p className="mt-2 text-sm text-ink-500">管理收藏、搜索历史、导入房源与个人偏好。</p>
        </div>

        <div
          className="mt-6 flex gap-1 overflow-x-auto rounded-full bg-white p-1 shadow-card ring-1 ring-ink-100/60 sm:inline-flex"
          role="tablist"
          aria-label="工作台分区"
        >
          {TABS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              role="tab"
              aria-selected={activeTab === tab.key}
              onClick={() => selectTab(tab.key)}
              className={[
                'shrink-0 rounded-full px-4 py-2 text-sm font-medium transition-colors',
                activeTab === tab.key
                  ? 'bg-brand-600 text-white shadow-sm'
                  : 'text-ink-600 hover:bg-ink-100 hover:text-ink-900',
              ].join(' ')}
            >
              {tab.label}
            </button>
          ))}
        </div>

        <div className="mt-6" role="tabpanel" aria-label={TABS.find((tab) => tab.key === activeTab)?.label}>
          {activeTab === 'favorites' && <FavoritesTab />}
          {activeTab === 'history' && <HistoryTab />}
          {activeTab === 'imports' && <ImportsTab />}
          {activeTab === 'notifications' && <NotificationsTab />}
          {activeTab === 'settings' && <SettingsTab />}
        </div>
      </div>
    </SiteLayout>
  )
}

export default UserWorkspacePage

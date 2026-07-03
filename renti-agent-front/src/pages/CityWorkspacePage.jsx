import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'

import { BrandMark } from '../components/site/SiteHeader.jsx'
import Badge from '../components/ui/Badge.jsx'
import Button from '../components/ui/Button.jsx'
import { EmptyState, LoadingBlock, Spinner } from '../components/ui/Feedback.jsx'
import RecommendationCard from '../features/workspace/RecommendationCard.jsx'
import RequirementSummary from '../features/workspace/RequirementSummary.jsx'
import SearchPanel from '../features/workspace/SearchPanel.jsx'
import ToolTraceBar from '../features/workspace/ToolTraceBar.jsx'
import {
  MARKER_LIMIT,
  escapeHtml,
  formatPrice,
  imgSrc,
  normalizeImportedListing,
  normalizeMarker,
  normalizeRecommendation,
  radiusToZoom,
  readField,
  sortListings,
  toNumber,
} from '../features/workspace/utils.js'
import useAmap from '../hooks/useAmap.js'
import { agentService, searchService } from '../services/searchService.js'
import { userService } from '../services/userService.js'
import { useAuthStore } from '../store/authStore.js'

const DEFAULT_CENTER = [121.4737, 31.2304]
const DEFAULT_PAGE_SIZE = 10

/** 列表骨架占位 */
function ListingSkeleton() {
  return (
    <div className="space-y-3" aria-hidden="true">
      {[0, 1, 2].map((key) => (
        <div key={key} className="animate-pulse rounded-2xl bg-white p-3 shadow-card ring-1 ring-ink-100/60">
          <div className="flex gap-3">
            <div className="h-20 w-24 rounded-xl bg-ink-100" />
            <div className="flex-1 space-y-2 py-1">
              <div className="h-3.5 w-3/4 rounded bg-ink-100" />
              <div className="h-3 w-1/2 rounded bg-ink-100" />
              <div className="h-3.5 w-1/3 rounded bg-ink-100" />
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}

/**
 * 城市地图工作台：左侧搜索/结果面板 + 右侧高德地图。
 * 功能对齐旧版 CityPage.vue：自然语言搜索、Agent 深度搜索、地图选点、
 * 半径圈、marker 联动、收藏、分页、user_import 自有房源模式。
 */
function CityWorkspacePage() {
  const { cityName } = useParams()
  const city = cityName || '上海'
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const mode = searchParams.get('mode') === 'user_import' ? 'user_import' : 'system_search'
  const isImportMode = mode === 'user_import'
  const { status: authStatus, user, fetchSession, logout } = useAuthStore()

  const { containerRef, mapRef, amap, ready: mapReady, error: mapError } = useAmap({
    center: DEFAULT_CENTER,
    zoom: 12,
  })

  const [settings, setSettings] = useState(null)
  const [settingsLoaded, setSettingsLoaded] = useState(false)
  const [queryText, setQueryText] = useState(() => searchParams.get('q') || '')
  const [search, setSearch] = useState({ status: 'idle', error: '', result: null, engine: 'intent' })
  const [radius, setRadius] = useState(1000)
  const [sort, setSort] = useState('score_desc')
  const [page, setPage] = useState(1)
  const [selectedId, setSelectedId] = useState('')
  const [markerScope, setMarkerScope] = useState('page')
  const [panelOpen, setPanelOpen] = useState(true)
  const [menuOpen, setMenuOpen] = useState(false)
  const [favoriteIds, setFavoriteIds] = useState(() => new Set())
  const [actionError, setActionError] = useState('')
  const [imported, setImported] = useState({ status: 'idle', error: '', items: [] })
  const [resolvingPoint, setResolvingPoint] = useState(false)

  const seqRef = useRef(0)
  const markersRef = useRef([])
  const infoWindowRef = useRef(null)
  const targetMarkerRef = useRef(null)
  const circleRef = useRef(null)
  const lastSearchRef = useRef(null)
  const autoSearchedRef = useRef(false)
  const menuRef = useRef(null)
  const listingIndexRef = useRef(new Map())

  /** 地图回调等异步场景读取最新状态 */
  const stateRef = useRef({})
  stateRef.current = { city, mode, queryText, radius, sort, settings, navigate }

  useEffect(() => {
    if (authStatus === 'idle') fetchSession()
  }, [authStatus, fetchSession])

  useEffect(() => {
    if (!menuOpen) return undefined
    const onClickOutside = (event) => {
      if (menuRef.current && !menuRef.current.contains(event.target)) setMenuOpen(false)
    }
    document.addEventListener('mousedown', onClickOutside)
    return () => document.removeEventListener('mousedown', onClickOutside)
  }, [menuOpen])

  /* ---------------- 首载：用户设置 + 收藏列表 ---------------- */

  useEffect(() => {
    let disposed = false
    userService
      .getSettings()
      .then((data) => {
        if (disposed) return
        const value = data?.settings || data || {}
        setSettings(value)
        const savedRadius = toNumber(readField(value, 'defaultRadiusMeters', 'radiusMeters', 'default_radius_meters'))
        if (Number.isFinite(savedRadius) && savedRadius > 0) setRadius(savedRadius)
        const savedSort = readField(value, 'defaultSort', 'default_sort')
        if (savedSort === 'price_asc' || savedSort === 'score_desc') setSort(savedSort)
      })
      .catch(() => {})
      .finally(() => {
        if (!disposed) setSettingsLoaded(true)
      })

    userService
      .getFavorites()
      .then((data) => {
        if (disposed) return
        const rows = Array.isArray(data?.favorites) ? data.favorites : []
        setFavoriteIds(
          new Set(
            rows
              .map((row) => String(readField(row, 'listingId', 'listing_id') ?? row?.listing?.id ?? ''))
              .filter(Boolean),
          ),
        )
      })
      .catch(() => {})

    return () => {
      disposed = true
    }
  }, [])

  const pageSize = useMemo(() => {
    const size = toNumber(readField(settings || {}, 'listingPageSize', 'listing_page_size'))
    return Number.isFinite(size) && size > 0 ? size : DEFAULT_PAGE_SIZE
  }, [settings])

  /* ---------------- 搜索链路 ---------------- */

  const runSearch = useCallback(
    async ({ engine = 'intent', queryText: qArg, source = 'text', center = null, label = '', radiusMeters } = {}) => {
      const current = stateRef.current
      const q = qArg !== undefined ? qArg : current.queryText.trim()
      const effectiveRadius = radiusMeters ?? current.radius
      const seq = ++seqRef.current

      setSearch({ status: 'loading', error: '', result: null, engine })
      setSelectedId('')
      setPage(1)
      setActionError('')
      infoWindowRef.current?.close?.()

      const payload = {
        queryText: q,
        city: current.city,
        source,
        radiusMeters: effectiveRadius,
        sort: current.sort,
      }
      const modelProfile = readField(current.settings || {}, 'modelProfile', 'model_profile')
      if (modelProfile) payload.modelProfile = modelProfile
      if (center) {
        payload.center = { longitude: center[0], latitude: center[1], label: label || '地图选点' }
        payload.longitude = center[0]
        payload.latitude = center[1]
        payload.label = label || '地图选点'
      }

      try {
        const data =
          engine === 'agent' ? await agentService.rentalSearch(payload) : await searchService.mapIntent(payload)
        if (seq !== seqRef.current) return
        lastSearchRef.current = { engine, queryText: q, source, center, label }
        setSearch({ status: 'ready', error: '', result: data || null, engine })
        const next = new URLSearchParams()
        if (q) next.set('q', q)
        next.set('mode', current.mode)
        setSearchParams(next, { replace: true })
      } catch (err) {
        if (seq !== seqRef.current) return
        setSearch({ status: 'error', error: err?.message || '搜索请求失败，请稍后重试', result: null, engine })
      }
    },
    [setSearchParams],
  )

  /** URL ?q= 初始搜索（等设置加载完成，半径/模型档位就绪后触发一次） */
  useEffect(() => {
    if (autoSearchedRef.current || !settingsLoaded || isImportMode) return
    autoSearchedRef.current = true
    const q = searchParams.get('q')
    if (q && q.trim()) runSearch({ queryText: q.trim() })
  }, [settingsLoaded, isImportMode, searchParams, runSearch])

  /* ---------------- user_import 模式：导入房源 ---------------- */

  const loadImported = useCallback(async () => {
    setImported((prev) => ({ ...prev, status: 'loading', error: '' }))
    try {
      const data = await userService.getImportedListings(stateRef.current.city)
      const rows = Array.isArray(data?.listings) ? data.listings : []
      setImported({
        status: 'ready',
        error: '',
        items: rows.map((row) => normalizeImportedListing(row, stateRef.current.city)).filter(Boolean),
      })
    } catch (err) {
      setImported({ status: 'error', error: err?.message || '导入房源加载失败', items: [] })
    }
  }, [])

  useEffect(() => {
    if (isImportMode) loadImported()
  }, [isImportMode, loadImported])

  /* ---------------- 派生数据 ---------------- */

  const result = search.result
  const needsClarification = readField(result || {}, 'intent') === 'needs_clarification'

  const recommendations = useMemo(() => {
    const rows = Array.isArray(result?.recommendations) ? result.recommendations : []
    return rows.map(normalizeRecommendation)
  }, [result])

  const listings = useMemo(() => {
    if (isImportMode) {
      const keyword = queryText.trim().toLowerCase()
      const rows = keyword
        ? imported.items.filter((item) => `${item.title} ${item.location} ${item.community}`.toLowerCase().includes(keyword))
        : imported.items
      return sortListings(rows, sort)
    }
    return sortListings(recommendations, sort)
  }, [isImportMode, imported.items, queryText, recommendations, sort])

  useEffect(() => {
    listingIndexRef.current = new Map(listings.map((item) => [item.id, item]))
  }, [listings])

  const totalPages = Math.max(1, Math.ceil(listings.length / pageSize))
  const safePage = Math.min(page, totalPages)
  const pagedListings = useMemo(
    () => listings.slice((safePage - 1) * pageSize, safePage * pageSize),
    [listings, safePage, pageSize],
  )

  const markers = useMemo(() => {
    const rows = isImportMode
      ? listings
      : Array.isArray(result?.markers) && result.markers.length > 0
        ? result.markers.map(normalizeMarker)
        : recommendations
    return rows.filter((item) => Number.isFinite(item.longitude) && Number.isFinite(item.latitude))
  }, [isImportMode, listings, result, recommendations])

  const visibleMarkers = useMemo(() => {
    if (markerScope === 'all') return markers.slice(0, MARKER_LIMIT)
    const ids = new Set(pagedListings.map((item) => item.id))
    return markers.filter((item) => ids.has(item.id))
  }, [markers, markerScope, pagedListings])

  const resultCenter = useMemo(() => {
    const center = result?.center
    const lng = toNumber(readField(center || {}, 'longitude', 'lng'))
    const lat = toNumber(readField(center || {}, 'latitude', 'lat'))
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null
    return { lng, lat, label: center?.label || '目标位置' }
  }, [result])

  const resultRadius = useMemo(() => {
    const value = toNumber(readField(result || {}, 'radiusMeters', 'radius_meters'))
    return Number.isFinite(value) && value > 0 ? value : radius
  }, [result, radius])

  const agentIntent = result?.agent?.intent || null
  const warnings = Array.isArray(result?.warnings) ? result.warnings.filter(Boolean) : []

  /* ---------------- 地图：InfoWindow / marker / 目标点 ---------------- */

  const openInfoWindow = useCallback(
    (item) => {
      const map = mapRef.current
      if (!map || !amap || !item || !Number.isFinite(item.longitude) || !Number.isFinite(item.latitude)) return

      const root = document.createElement('div')
      root.className = 'w-60 overflow-hidden rounded-xl'
      const src = imgSrc(item.image)
      const priceText = Number.isFinite(item.rentPrice)
        ? `${formatPrice(item.rentPrice)}/月`
        : item.priceLabel || '价格待确认'
      root.innerHTML = `
        ${
          src
            ? `<img src="${escapeHtml(src)}" alt="${escapeHtml(item.title)}" class="h-28 w-full object-cover" onerror="this.style.display='none'" />`
            : '<div class="flex h-16 items-center justify-center bg-gradient-to-br from-brand-50 to-sky-100 text-xs text-ink-400">暂无图片</div>'
        }
        <div class="space-y-1 bg-white p-3">
          <p class="truncate text-sm font-semibold text-ink-900">${escapeHtml(item.title)}</p>
          <p class="text-sm font-semibold text-brand-600">${escapeHtml(priceText)}</p>
        </div>`
      const detailButton = document.createElement('button')
      detailButton.type = 'button'
      detailButton.textContent = '查看详情'
      detailButton.className =
        'block w-full bg-brand-600 px-3 py-2 text-center text-xs font-medium text-white transition hover:bg-brand-700'
      detailButton.addEventListener('click', () => {
        stateRef.current.navigate(`/property/${encodeURIComponent(item.id)}`)
      })
      root.appendChild(detailButton)

      if (!infoWindowRef.current) {
        infoWindowRef.current = new amap.InfoWindow({ offset: new amap.Pixel(0, -36) })
      }
      infoWindowRef.current.setContent(root)
      infoWindowRef.current.open(map, [item.longitude, item.latitude])
    },
    [amap, mapRef],
  )

  /** 房源 marker 渲染（当前页 / 前 100 条） */
  useEffect(() => {
    const map = mapRef.current
    if (!mapReady || !map || !amap) return
    markersRef.current.forEach((marker) => marker.setMap(null))
    markersRef.current = visibleMarkers.map((marker) => {
      const selected = marker.id === selectedId
      const element = document.createElement('button')
      element.type = 'button'
      element.className = [
        'whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-semibold shadow-float ring-1 transition',
        selected
          ? 'bg-brand-600 text-white ring-brand-700'
          : marker.withinRadius === false
            ? 'bg-white/90 text-ink-400 ring-ink-200'
            : 'bg-white text-ink-900 ring-ink-200 hover:ring-brand-300',
      ].join(' ')
      element.textContent = marker.priceLabel || marker.title || '房源'
      element.setAttribute('aria-label', `${marker.title || '房源'} ${marker.priceLabel || ''}`)

      const instance = new amap.Marker({
        position: [marker.longitude, marker.latitude],
        content: element,
        anchor: 'bottom-center',
        zIndex: selected ? 130 : 110,
        title: marker.title || '',
        map,
      })
      instance.on('click', () => {
        setSelectedId(marker.id)
        const item = listingIndexRef.current.get(marker.id) || marker
        openInfoWindow(item)
        document
          .getElementById(`listing-card-${marker.id}`)
          ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
      })
      return instance
    })
  }, [mapReady, amap, mapRef, visibleMarkers, selectedId, openInfoWindow])

  /** 目标点 marker + 半径圈；搜索结果变化时定位地图 */
  useEffect(() => {
    const map = mapRef.current
    if (!mapReady || !map || !amap) return
    targetMarkerRef.current?.setMap(null)
    targetMarkerRef.current = null
    circleRef.current?.setMap(null)
    circleRef.current = null
    if (!resultCenter) return

    const element = document.createElement('div')
    element.className = 'flex flex-col items-center'
    element.innerHTML = `
      <span class="whitespace-nowrap rounded-full bg-ink-950 px-2.5 py-1 text-xs font-medium text-white shadow-float">${escapeHtml(resultCenter.label)}</span>
      <span class="mt-1 h-3 w-3 rounded-full border-2 border-white bg-brand-600 shadow"></span>`
    targetMarkerRef.current = new amap.Marker({
      position: [resultCenter.lng, resultCenter.lat],
      content: element,
      anchor: 'bottom-center',
      zIndex: 150,
      map,
    })
    circleRef.current = new amap.Circle({
      center: [resultCenter.lng, resultCenter.lat],
      radius: resultRadius,
      strokeColor: '#1d60f1',
      strokeOpacity: 0.7,
      strokeWeight: 1.2,
      fillColor: '#1d60f1',
      fillOpacity: 0.08,
      bubble: true,
    })
    map.add(circleRef.current)
    map.setZoomAndCenter(radiusToZoom(resultRadius), [resultCenter.lng, resultCenter.lat])
  }, [mapReady, amap, mapRef, resultCenter, resultRadius])

  /** 地图点击 → resolvePlace → 以选点为中心搜索 */
  const handleMapClickRef = useRef(null)
  handleMapClickRef.current = async (lng, lat) => {
    const current = stateRef.current
    if (current.mode === 'user_import') return
    setResolvingPoint(true)
    let center = [lng, lat]
    let label = '地图选点'
    try {
      const data = await searchService.resolvePlace({
        name: '地图选点',
        type: 'custom_point',
        longitude: lng,
        latitude: lat,
        city: current.city,
      })
      const place = data?.place || data || {}
      const resolvedLng = toNumber(readField(place, 'longitude', 'lng'))
      const resolvedLat = toNumber(readField(place, 'latitude', 'lat'))
      if (Number.isFinite(resolvedLng) && Number.isFinite(resolvedLat)) center = [resolvedLng, resolvedLat]
      if (place.name && place.name !== 'custom_point') label = place.name
    } catch {
      // 解析失败时直接以点击坐标为中心搜索
    } finally {
      setResolvingPoint(false)
    }
    await runSearch({ source: 'map_click', center, label })
  }

  useEffect(() => {
    const map = mapRef.current
    if (!mapReady || !map) return undefined
    const handler = (event) => {
      handleMapClickRef.current?.(event.lnglat.getLng(), event.lnglat.getLat())
    }
    map.on('click', handler)
    return () => map.off('click', handler)
  }, [mapReady, mapRef])

  /** 卸载时清理覆盖物（map.destroy 由 useAmap 负责） */
  useEffect(
    () => () => {
      markersRef.current.forEach((marker) => marker.setMap(null))
      markersRef.current = []
      targetMarkerRef.current?.setMap(null)
      circleRef.current?.setMap(null)
      infoWindowRef.current?.close?.()
    },
    [],
  )

  /* ---------------- 交互 ---------------- */

  const handleSelectListing = (item) => {
    setSelectedId(item.id)
    const map = mapRef.current
    if (map && Number.isFinite(item.longitude) && Number.isFinite(item.latitude)) {
      map.setZoomAndCenter(16, [item.longitude, item.latitude])
      openInfoWindow(item)
    }
  }

  const handleRadiusChange = (value) => {
    setRadius(value)
    if (stateRef.current.mode === 'user_import') return
    const last = lastSearchRef.current
    if (last) {
      runSearch({
        engine: last.engine,
        queryText: last.queryText,
        source: last.source,
        center: last.center,
        label: last.label,
        radiusMeters: value,
      })
    }
  }

  const handleRetry = () => {
    const last = lastSearchRef.current
    if (last) {
      runSearch({ engine: last.engine, queryText: last.queryText, source: last.source, center: last.center, label: last.label })
    } else {
      runSearch({ engine: search.engine })
    }
  }

  const handleToggleFavorite = async (item) => {
    setActionError('')
    try {
      if (favoriteIds.has(item.id)) {
        await userService.removeFavorite(item.id)
        setFavoriteIds((prev) => {
          const next = new Set(prev)
          next.delete(item.id)
          return next
        })
      } else {
        await userService.saveFavorite({
          listingId: item.id,
          listing: {
            id: item.id,
            title: item.title,
            price: Number.isFinite(item.rentPrice) ? `¥${item.rentPrice}/月` : item.priceLabel || '价格待确认',
            location: [item.district, item.businessArea].filter(Boolean).join(' ') || item.location || '',
            image: item.image || '',
          },
        })
        setFavoriteIds((prev) => new Set(prev).add(item.id))
      }
    } catch (err) {
      setActionError(err?.message || '收藏操作失败，请稍后重试')
    }
  }

  const handleLogout = async () => {
    setMenuOpen(false)
    await logout()
    navigate('/')
  }

  const changePage = (next) => {
    setPage(Math.min(Math.max(1, next), totalPages))
    setSelectedId('')
  }

  const nickname = user?.nickname || user?.displayName || user?.email || '用户'
  const avatarChar = nickname.trim().charAt(0).toUpperCase() || 'U'
  const searchLoading = search.status === 'loading'

  /* ---------------- 渲染 ---------------- */

  const renderResultsBody = () => {
    if (isImportMode) {
      if (imported.status === 'loading' || imported.status === 'idle') return <ListingSkeleton />
      if (imported.status === 'error') {
        return (
          <div className="rounded-xl bg-rose-50 p-3 text-xs leading-5 text-rose-700 ring-1 ring-rose-100">
            <p>{imported.error}</p>
            <Button variant="secondary" size="sm" className="mt-2" onClick={loadImported}>
              重试
            </Button>
          </div>
        )
      }
      if (imported.items.length === 0) {
        return (
          <EmptyState
            icon="📦"
            title="还没有导入房源"
            description="自有房源模式只分析你导入的数据。先去我的工作台导入候选房源，再回来做地图分析。"
            action={
              <Button size="sm" onClick={() => navigate('/workspace')}>
                去导入房源
              </Button>
            }
          />
        )
      }
      if (listings.length === 0) {
        return <EmptyState icon="🔍" title="没有匹配的导入房源" description="换个关键词，或清空筛选查看全部导入房源。" />
      }
      return null
    }

    if (search.status === 'idle') {
      return (
        <EmptyState
          icon="🗺️"
          title="从一句话或一次点击开始"
          description={`输入${city}的租房需求，或直接点击右侧地图任意位置，以该点为中心搜索周边房源。`}
        />
      )
    }
    if (search.status === 'loading') {
      return (
        <div className="space-y-3">
          <p className="flex items-center gap-2 text-xs text-ink-500">
            <Spinner className="h-3.5 w-3.5" />
            {search.engine === 'agent' ? 'Agent 正在解析需求并深度检索…' : `正在查询${city}房源…`}
          </p>
          <ListingSkeleton />
        </div>
      )
    }
    if (search.status === 'error') {
      return (
        <div className="rounded-xl bg-rose-50 p-3 text-xs leading-5 text-rose-700 ring-1 ring-rose-100">
          <p>{search.error}</p>
          <Button variant="secondary" size="sm" className="mt-2" onClick={handleRetry}>
            重试
          </Button>
        </div>
      )
    }
    if (needsClarification) {
      return (
        <div className="rounded-xl bg-amber-50 p-3 text-xs leading-5 text-amber-800 ring-1 ring-amber-100">
          <p className="font-medium">需要补充信息</p>
          <p className="mt-1">{result?.summary || '当前描述不足以定位目标区域。'}</p>
          <p className="mt-1 text-amber-700">试试补充具体地点（如小区、地铁站、公司地址）和预算，再重新搜索。</p>
        </div>
      )
    }
    if (listings.length === 0) {
      return (
        <EmptyState
          icon="🏠"
          title="没有找到匹配房源"
          description="试试扩大搜索半径、更换目标位置，或放宽预算与户型条件。"
        />
      )
    }
    return null
  }

  const showList = isImportMode
    ? imported.status === 'ready' && listings.length > 0
    : search.status === 'ready' && !needsClarification && listings.length > 0

  return (
    <div className="flex h-screen flex-col bg-ink-50">
      {/* 顶部工具条 */}
      <header className="z-20 flex h-14 shrink-0 items-center justify-between gap-3 border-b border-ink-100 bg-white px-4">
        <div className="flex min-w-0 items-center gap-2.5">
          <Link
            to="/cities"
            aria-label="返回城市选择"
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-ink-500 transition hover:bg-ink-100 hover:text-ink-900"
          >
            <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
              <path
                fillRule="evenodd"
                d="M17 10a.75.75 0 0 1-.75.75H5.612l4.158 3.96a.75.75 0 1 1-1.04 1.08l-5.5-5.25a.75.75 0 0 1 0-1.08l5.5-5.25a.75.75 0 1 1 1.04 1.08L5.612 9.25H16.25A.75.75 0 0 1 17 10Z"
                clipRule="evenodd"
              />
            </svg>
          </Link>
          <Link to="/" aria-label="Renti Agent 首页" className="shrink-0">
            <BrandMark className="h-8 w-8" />
          </Link>
          <h1 className="truncate text-sm font-semibold text-ink-900">{city} · 城市工作台</h1>
          <Badge tone={isImportMode ? 'warning' : 'brand'}>{isImportMode ? '自有房源模式' : '平台房源模式'}</Badge>
        </div>

        <div className="relative shrink-0" ref={menuRef}>
          {authStatus === 'authenticated' && user ? (
            <>
              <button
                type="button"
                onClick={() => setMenuOpen((open) => !open)}
                aria-label="用户菜单"
                aria-expanded={menuOpen}
                className="flex items-center gap-2 rounded-full py-1 pl-1 pr-2 transition hover:bg-ink-100"
              >
                <span
                  className="flex h-7 w-7 items-center justify-center rounded-full bg-brand-600 text-xs font-semibold text-white"
                  aria-hidden="true"
                >
                  {avatarChar}
                </span>
                <span className="hidden max-w-[7rem] truncate text-xs font-medium text-ink-700 sm:block">{nickname}</span>
              </button>
              {menuOpen && (
                <div className="absolute right-0 mt-2 w-40 overflow-hidden rounded-2xl bg-white py-1.5 shadow-float ring-1 ring-ink-100 animate-fade-in">
                  <Link
                    to="/workspace"
                    onClick={() => setMenuOpen(false)}
                    className="block px-4 py-2 text-sm text-ink-700 transition hover:bg-ink-50"
                  >
                    我的工作台
                  </Link>
                  <button
                    type="button"
                    onClick={handleLogout}
                    className="block w-full px-4 py-2 text-left text-sm text-rose-600 transition hover:bg-rose-50"
                  >
                    退出登录
                  </button>
                </div>
              )}
            </>
          ) : (
            <Button size="sm" variant="ghost" onClick={() => navigate('/login')}>
              登录
            </Button>
          )}
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        {/* 左侧面板 */}
        {panelOpen ? (
          <aside className="flex w-full max-w-[420px] shrink-0 flex-col border-r border-ink-100 bg-white sm:w-[420px]">
            <div className="border-b border-ink-100 p-4">
              <div className="mb-2.5 flex items-center justify-between">
                <h2 className="text-sm font-semibold text-ink-900">
                  {isImportMode ? '筛选导入房源' : '描述你的租房需求'}
                </h2>
                <button
                  type="button"
                  onClick={() => setPanelOpen(false)}
                  aria-label="收起面板"
                  className="rounded-full p-1.5 text-ink-400 transition hover:bg-ink-100 hover:text-ink-700"
                >
                  <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
                    <path
                      fillRule="evenodd"
                      d="M12.78 5.22a.75.75 0 0 1 0 1.06L9.06 10l3.72 3.72a.75.75 0 1 1-1.06 1.06l-4.25-4.25a.75.75 0 0 1 0-1.06l4.25-4.25a.75.75 0 0 1 1.06 0Z"
                      clipRule="evenodd"
                    />
                  </svg>
                </button>
              </div>
              <SearchPanel
                value={queryText}
                onChange={setQueryText}
                onSearch={() => runSearch({ engine: 'intent' })}
                onAgentSearch={() => runSearch({ engine: 'agent' })}
                loading={searchLoading}
                engine={search.engine}
                isImportMode={isImportMode}
                city={city}
              />
            </div>

            <div className="flex-1 space-y-4 overflow-y-auto p-4 scrollbar-thin">
              {isImportMode && (
                <div className="rounded-xl bg-amber-50 px-3 py-2.5 text-xs leading-5 text-amber-800 ring-1 ring-amber-100">
                  当前仅分析你导入的房源，不会查询平台房源库。
                </div>
              )}

              {actionError && (
                <p className="rounded-xl bg-rose-50 px-3 py-2 text-xs text-rose-700 ring-1 ring-rose-100">{actionError}</p>
              )}

              {/* 摘要 / agent 意图 / 警告 / 步骤条（仅平台模式且有结果时） */}
              {!isImportMode && search.status === 'ready' && result?.summary && !needsClarification && (
                <p className="rounded-xl bg-brand-50/60 px-3 py-2.5 text-xs leading-5 text-ink-700 ring-1 ring-brand-100">
                  {result.summary}
                </p>
              )}

              {!isImportMode && search.engine === 'agent' && agentIntent && (
                <div className="flex flex-wrap gap-1.5" aria-label="Agent 解析意图">
                  {Number.isFinite(toNumber(readField(agentIntent, 'budgetMax', 'budget_max'))) && (
                    <Badge tone="brand">预算 ≤ {formatPrice(readField(agentIntent, 'budgetMax', 'budget_max'))}</Badge>
                  )}
                  {agentIntent.layout && <Badge>户型 {agentIntent.layout}</Badge>}
                  {Number.isFinite(toNumber(agentIntent.confidence)) && (
                    <Badge tone={toNumber(agentIntent.confidence) >= 0.7 ? 'success' : 'warning'}>
                      置信度 {Math.round(toNumber(agentIntent.confidence) * 100)}%
                    </Badge>
                  )}
                  {(Array.isArray(agentIntent.preferences) ? agentIntent.preferences : []).slice(0, 2).map((pref) => (
                    <Badge key={pref} tone="info">
                      {pref}
                    </Badge>
                  ))}
                </div>
              )}

              {!isImportMode && warnings.length > 0 && (
                <ul className="space-y-1 rounded-xl bg-amber-50 px-3 py-2.5 text-xs leading-5 text-amber-800 ring-1 ring-amber-100">
                  {warnings.map((warning) => (
                    <li key={warning}>{warning}</li>
                  ))}
                </ul>
              )}

              {!isImportMode && <ToolTraceBar trace={result?.toolTrace} />}

              {!isImportMode && search.status === 'ready' && !needsClarification && (
                <RequirementSummary
                  parsed={result?.parsed}
                  radius={radius}
                  onRadiusChange={handleRadiusChange}
                  sort={sort}
                  onSortChange={setSort}
                  disabled={searchLoading}
                />
              )}

              {renderResultsBody()}

              {showList && (
                <>
                  <div className="flex items-center justify-between gap-2">
                    <p className="text-xs font-medium text-ink-500">
                      共 {isImportMode ? listings.length : toNumber(result?.total, listings.length) || listings.length} 条
                      · 第 {safePage}/{totalPages} 页
                    </p>
                    <div className="inline-flex rounded-full bg-ink-100 p-0.5" role="group" aria-label="地图 marker 范围">
                      {[
                        { value: 'page', label: '当前页' },
                        { value: 'all', label: '前100条' },
                      ].map((option) => (
                        <button
                          key={option.value}
                          type="button"
                          onClick={() => setMarkerScope(option.value)}
                          aria-pressed={markerScope === option.value}
                          className={[
                            'rounded-full px-2.5 py-1 text-xs font-medium transition',
                            markerScope === option.value
                              ? 'bg-white text-ink-900 shadow-sm'
                              : 'text-ink-500 hover:text-ink-800',
                          ].join(' ')}
                        >
                          {option.label}
                        </button>
                      ))}
                    </div>
                  </div>

                  <div className="space-y-3">
                    {pagedListings.map((item) => (
                      <RecommendationCard
                        key={item.id}
                        item={item}
                        active={selectedId === item.id}
                        favorite={favoriteIds.has(item.id)}
                        onSelect={handleSelectListing}
                        onDetail={(listing) => navigate(`/property/${encodeURIComponent(listing.id)}`)}
                        onToggleFavorite={handleToggleFavorite}
                      />
                    ))}
                  </div>

                  {totalPages > 1 && (
                    <div className="flex items-center justify-center gap-3 pb-2">
                      <Button
                        variant="secondary"
                        size="sm"
                        disabled={safePage <= 1}
                        onClick={() => changePage(safePage - 1)}
                        aria-label="上一页"
                      >
                        上一页
                      </Button>
                      <span className="text-xs text-ink-500">
                        {safePage} / {totalPages}
                      </span>
                      <Button
                        variant="secondary"
                        size="sm"
                        disabled={safePage >= totalPages}
                        onClick={() => changePage(safePage + 1)}
                        aria-label="下一页"
                      >
                        下一页
                      </Button>
                    </div>
                  )}
                </>
              )}
            </div>
          </aside>
        ) : (
          <div className="flex w-12 shrink-0 flex-col items-center gap-3 border-r border-ink-100 bg-white py-3">
            <button
              type="button"
              onClick={() => setPanelOpen(true)}
              aria-label="展开面板"
              className="rounded-full p-1.5 text-ink-400 transition hover:bg-ink-100 hover:text-ink-700"
            >
              <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4" aria-hidden="true">
                <path
                  fillRule="evenodd"
                  d="M7.22 14.78a.75.75 0 0 1 0-1.06L10.94 10 7.22 6.28a.75.75 0 1 1 1.06-1.06l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 0 1-1.06 0Z"
                  clipRule="evenodd"
                />
              </svg>
            </button>
            {listings.length > 0 && (
              <span className="rounded-full bg-brand-50 px-1.5 py-0.5 text-[10px] font-semibold text-brand-700">
                {listings.length}
              </span>
            )}
          </div>
        )}

        {/* 右侧地图 */}
        <main className="relative min-w-0 flex-1">
          <div ref={containerRef} className="absolute inset-0" role="application" aria-label={`${city}房源地图`} />

          {!mapReady && !mapError && (
            <div className="absolute inset-0 flex items-center justify-center bg-ink-50">
              <LoadingBlock text="正在加载高德地图…" className="py-0" />
            </div>
          )}
          {mapError && (
            <div className="absolute inset-0 flex items-center justify-center bg-ink-50">
              <EmptyState
                icon="🛰️"
                title="地图加载失败"
                description="请检查网络或高德地图 Key 配置后刷新页面重试。"
              />
            </div>
          )}

          {(searchLoading || resolvingPoint) && (
            <div className="absolute left-1/2 top-4 z-10 flex -translate-x-1/2 items-center gap-2 rounded-full bg-white/95 px-4 py-2 text-xs font-medium text-ink-700 shadow-float ring-1 ring-ink-100">
              <Spinner className="h-3.5 w-3.5" />
              {resolvingPoint ? '正在解析地图选点…' : search.engine === 'agent' ? 'Agent 深度搜索中…' : '正在搜索房源…'}
            </div>
          )}

          {isImportMode && mapReady && (
            <div className="absolute left-1/2 top-4 z-10 -translate-x-1/2 rounded-full bg-amber-50/95 px-4 py-2 text-xs font-medium text-amber-800 shadow-card ring-1 ring-amber-200">
              自有房源模式：地图仅展示你导入的房源
            </div>
          )}

          {!isImportMode && mapReady && search.status === 'idle' && (
            <div className="absolute bottom-6 left-1/2 z-10 -translate-x-1/2 rounded-full bg-white/95 px-4 py-2 text-xs text-ink-500 shadow-card ring-1 ring-ink-100">
              点击地图任意位置，以该点为中心搜索周边房源
            </div>
          )}
        </main>
      </div>
    </div>
  )
}

export default CityWorkspacePage

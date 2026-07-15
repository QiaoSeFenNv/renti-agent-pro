import { listingService } from '../../services/searchService.js'

/** 半径快捷选项（米） */
export const RADIUS_OPTIONS = [500, 1000, 2000, 3000]

/** 地图 marker 展示上限（前 100 条） */
export const MARKER_LIMIT = 100

/**
 * 兜底字段读取：联调期后端可能返回 camelCase 或 snake_case，
 * 按传入顺序返回第一个非空值。
 */
export function readField(obj, ...keys) {
  if (!obj || typeof obj !== 'object') return undefined
  for (const key of keys) {
    const value = obj[key]
    if (value !== undefined && value !== null) return value
  }
  return undefined
}

/** 宽松数字解析，失败返回 fallback（默认 NaN 以便 Number.isFinite 判断） */
export function toNumber(value, fallback = Number.NaN) {
  if (value === undefined || value === null || value === '') return fallback
  const num = Number(value)
  return Number.isFinite(num) ? num : fallback
}

/** 外站图片走后端代理；已代理或相对路径原样返回 */
export function imgSrc(url) {
  const image = String(url || '').trim()
  if (!image || image.startsWith('/api/assets/listing-image')) return image
  if (/^https?:\/\//i.test(image)) return listingService.proxyImageUrl(image)
  return image
}

/** ¥5,200 形式的价格文案 */
export function formatPrice(value) {
  const num = toNumber(value)
  if (!Number.isFinite(num)) return ''
  return `¥${Math.round(num).toLocaleString('zh-CN')}`
}

/** 距离文案：1.2km / 350m */
export function formatDistance(meters) {
  const num = toNumber(meters)
  if (!Number.isFinite(num)) return ''
  if (num >= 1000) return `${(num / 1000).toFixed(1)}km`
  return `${Math.round(num)}m`
}

/** 简单 HTML 转义（marker/InfoWindow 动态内容用） */
export function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

/**
 * 推荐结果标准化（camelCase / snake_case 双读）。
 */
export function normalizeRecommendation(item = {}) {
  const detail = item.detail || null
  const rawImages = Array.isArray(item.images) && item.images.length > 0
    ? item.images
    : Array.isArray(detail?.images)
      ? detail.images
      : []
  const image = item.image || detail?.image || rawImages[0] || ''
  const images = rawImages.length > 0 ? rawImages : image ? [image] : []
  const rentPrice = toNumber(readField(item, 'rentPrice', 'rent_price'))
  const riskNotes = readField(item, 'riskNotes', 'risk_notes')
  const riskTags = readField(item, 'riskTags', 'risk_tags')
  const withinRadiusRaw = readField(item, 'withinRadius', 'within_radius')

  return {
    id: String(readField(item, 'id', 'listingId', 'listing_id') ?? ''),
    title: item.title || item.community || '未命名房源',
    community: item.community || '',
    district: item.district || '',
    businessArea: readField(item, 'businessArea', 'business_area') || '',
    longitude: toNumber(readField(item, 'longitude', 'lng')),
    latitude: toNumber(readField(item, 'latitude', 'lat')),
    rentPrice,
    priceLabel: Number.isFinite(rentPrice) ? formatPrice(rentPrice) : '价格待确认',
    layout: item.layout || '',
    areaSqm: toNumber(readField(item, 'areaSqm', 'area_sqm')),
    rentType: readField(item, 'rentType', 'rent_type') || '',
    tags: Array.isArray(item.tags) ? item.tags : [],
    riskTags: Array.isArray(riskTags) ? riskTags : [],
    riskNotes: Array.isArray(riskNotes) ? riskNotes : [],
    reasons: Array.isArray(item.reasons) ? item.reasons : [],
    score: toNumber(item.score, 0),
    match: item.match || '',
    distanceM: toNumber(readField(item, 'distanceM', 'distance_m')),
    hasTargetDistance: Boolean(readField(item, 'hasTargetDistance', 'has_target_distance')),
    withinRadius: withinRadiusRaw !== false,
    image,
    images,
    detail,
    verified: readField(item, 'verified') || readField(detail || {}, 'verified') || '',
  }
}

/** 地图 marker 标准化（双读） */
export function normalizeMarker(marker = {}) {
  const rentPrice = toNumber(readField(marker, 'rentPrice', 'rent_price'))
  const withinRadiusRaw = readField(marker, 'withinRadius', 'within_radius')
  return {
    id: String(readField(marker, 'id', 'listingId', 'listing_id') ?? ''),
    title: marker.title || '',
    longitude: toNumber(readField(marker, 'longitude', 'lng')),
    latitude: toNumber(readField(marker, 'latitude', 'lat')),
    rentPrice,
    priceLabel: Number.isFinite(rentPrice) ? formatPrice(rentPrice) : '房源',
    distanceM: toNumber(readField(marker, 'distanceM', 'distance_m')),
    withinRadius: withinRadiusRaw !== false,
  }
}

/**
 * 用户导入房源记录标准化：兼容 {listingId, listing:{...}} 或直接 listing 快照。
 */
export function normalizeImportedListing(record, city) {
  const listing = record?.listing && typeof record.listing === 'object' ? record.listing : record || {}
  const position = Array.isArray(listing.position) && listing.position.length === 2
    ? listing.position
    : [readField(listing, 'longitude', 'lng'), readField(listing, 'latitude', 'lat')]
  const longitude = toNumber(position?.[0])
  const latitude = toNumber(position?.[1])
  const id = String(readField(listing, 'id') ?? readField(record || {}, 'listingId', 'listing_id') ?? '')
  if (!id) return null
  const images = Array.isArray(listing.images) ? listing.images.filter(Boolean) : []
  const image = listing.image || images[0] || ''
  const rentPrice = toNumber(readField(listing, 'rentPrice', 'rent_price'))

  return {
    id,
    title: listing.title || '自有房源',
    community: listing.community || '',
    district: listing.district || '',
    businessArea: readField(listing, 'businessArea', 'business_area') || '',
    location: listing.location || listing.address || `${city} · 自有导入`,
    longitude,
    latitude,
    rentPrice,
    priceLabel: Number.isFinite(rentPrice) ? formatPrice(rentPrice) : String(listing.price || '价格待确认'),
    layout: listing.layout || '',
    areaSqm: toNumber(readField(listing, 'areaSqm', 'area_sqm')),
    tags: [],
    riskTags: [],
    riskNotes: [],
    reasons: [],
    score: toNumber(listing.score, 0),
    distanceM: Number.NaN,
    withinRadius: true,
    match: '自有导入',
    image,
    images: images.length > 0 ? images : image ? [image] : [],
    detail: listing.detail || null,
    imported: true,
  }
}

/** 本地排序：score_desc / price_asc */
export function sortListings(list, sort) {
  const rows = [...(Array.isArray(list) ? list : [])]
  if (sort === 'price_asc') {
    rows.sort((a, b) => {
      const left = Number.isFinite(a.rentPrice) ? a.rentPrice : Number.POSITIVE_INFINITY
      const right = Number.isFinite(b.rentPrice) ? b.rentPrice : Number.POSITIVE_INFINITY
      return left - right
    })
  } else {
    rows.sort((a, b) => (toNumber(b.score, 0) || 0) - (toNumber(a.score, 0) || 0))
  }
  return rows
}

/** 半径对应的地图缩放级别 */
export function radiusToZoom(radiusMeters) {
  const radius = toNumber(radiusMeters, 1000)
  if (radius <= 500) return 16
  if (radius <= 1000) return 15
  if (radius <= 2000) return 14
  return 13
}

/**
 * 核验状态 → 徽章展示。official_confirmed（官方接口确认）显示绿色「官方核验」，
 * platform_certified（来源平台自标官方核验）显示蓝色「平台核验」，official_failed 显示红色告警；
 * unverified / 空返回 null（不展示，避免噪声）。
 */
export function verificationBadge(verified) {
  switch (verified) {
    case 'official_confirmed':
      return { tone: 'success', label: '官方核验', icon: '✓', title: '已通过上海市住房租赁公共服务平台核验' }
    case 'platform_certified':
      return { tone: 'info', label: '平台核验', icon: '✓', title: '来源平台标注为官方核验房源（未经我方独立复核）' }
    case 'official_failed':
      return { tone: 'danger', label: '核验存疑', icon: '⚠', title: '官方核验未通过或信息不符，请谨慎核实' }
    default:
      return null
  }
}

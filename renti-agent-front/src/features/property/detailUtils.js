import { readField, toNumber } from '../workspace/utils.js'

/** 评分限制在 0-100 */
export function boundScore(value) {
  const num = toNumber(value, 0)
  if (!Number.isFinite(num)) return 0
  return Math.max(0, Math.min(100, Math.round(num)))
}

/** 坐标点是否有效 */
export function isCoordPoint(point) {
  if (!point || typeof point !== 'object') return false
  return Number.isFinite(toNumber(point.longitude)) && Number.isFinite(toNumber(point.latitude))
}

/** 两点直线距离（米） */
export function haversineMeters(a, b) {
  const toRadians = (value) => (value * Math.PI) / 180
  const radius = 6371000
  const latA = toRadians(toNumber(a.latitude, 0))
  const latB = toRadians(toNumber(b.latitude, 0))
  const deltaLat = toRadians(toNumber(b.latitude, 0) - toNumber(a.latitude, 0))
  const deltaLng = toRadians(toNumber(b.longitude, 0) - toNumber(a.longitude, 0))
  const h = Math.sin(deltaLat / 2) ** 2 + Math.cos(latA) * Math.cos(latB) * Math.sin(deltaLng / 2) ** 2
  return radius * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
}

/**
 * 详情数据标准化：detail 为主，listing 记录兜底（camelCase / snake_case 双读）。
 */
export function normalizeDetail(rawDetail, listing, listingId) {
  const detail = rawDetail || {}
  const source = listing || {}
  const images = [
    ...(Array.isArray(detail.images) ? detail.images : []),
    detail.image,
    ...(Array.isArray(source.images) ? source.images : []),
    source.image,
  ]
    .map((value) => String(value || '').trim())
    .filter((value, index, array) => value && array.indexOf(value) === index)

  const rentPrice = toNumber(readField(source, 'rentPrice', 'rent_price'))
  const areaSqm = toNumber(readField(source, 'areaSqm', 'area_sqm'))
  const dataSource = readField(detail, 'dataSource', 'data_source') || null

  return {
    id: String(listingId || readField(source, 'id') || ''),
    title: detail.title || source.title || '房源详情',
    address:
      detail.address ||
      [source.district, readField(source, 'businessArea', 'business_area'), source.community]
        .filter(Boolean)
        .join(' · ') ||
      '',
    images,
    image: images[0] || '',
    price:
      detail.price ||
      (Number.isFinite(rentPrice) ? `¥${Math.round(rentPrice).toLocaleString('zh-CN')}/月` : '价格待确认'),
    availability: detail.availability || '',
    layout: detail.layout || source.layout || '户型待补充',
    baths: detail.baths || '卫浴待补充',
    size: detail.size || (Number.isFinite(areaSqm) ? `${Math.round(areaSqm)}㎡` : '面积待补充'),
    floor: detail.floor || '楼层待补充',
    score: boundScore(readField(detail, 'score') ?? readField(source, 'score')),
    valueIndex: readField(detail, 'valueIndex', 'value_index') || null,
    environmentEvaluation: readField(detail, 'environmentEvaluation', 'environment_evaluation') || null,
    commuteEvaluation: readField(detail, 'commuteEvaluation', 'commute_evaluation') || null,
    insight: detail.insight || '',
    pros: Array.isArray(detail.pros) ? detail.pros : [],
    cons: Array.isArray(detail.cons) ? detail.cons : [],
    commute: Array.isArray(detail.commute) ? detail.commute : [],
    commuteMap: readField(detail, 'commuteMap', 'commute_map') || null,
    dataSource,
    verified: readField(detail, 'verified') || readField(source, 'verified') || '',
    sourceLabel:
      dataSource?.provider || readField(source, 'provider') || readField(source, 'source') || '',
  }
}

/**
 * AI 深度分析 detailPatch 合并：有值覆盖，无值保留原字段。
 */
export function mergeDetailPatch(detail, patch) {
  if (!patch || typeof patch !== 'object') return detail
  const merged = { ...detail }

  if (patch.insight) merged.insight = patch.insight
  if (Number.isFinite(toNumber(patch.score))) merged.score = boundScore(patch.score)
  if (Array.isArray(patch.pros) && patch.pros.length > 0) merged.pros = patch.pros
  if (Array.isArray(patch.cons) && patch.cons.length > 0) merged.cons = patch.cons
  if (Array.isArray(patch.commute) && patch.commute.length > 0) merged.commute = patch.commute

  const valueIndex = readField(patch, 'valueIndex', 'value_index')
  if (valueIndex) merged.valueIndex = { ...(detail.valueIndex || {}), ...valueIndex }
  const environment = readField(patch, 'environmentEvaluation', 'environment_evaluation')
  if (environment) merged.environmentEvaluation = { ...(detail.environmentEvaluation || {}), ...environment }
  const commuteEvaluation = readField(patch, 'commuteEvaluation', 'commute_evaluation')
  if (commuteEvaluation) merged.commuteEvaluation = { ...(detail.commuteEvaluation || {}), ...commuteEvaluation }

  const commuteMapPatch = readField(patch, 'commuteMap', 'commute_map')
  if (commuteMapPatch) {
    merged.commuteMap = {
      ...(detail.commuteMap || {}),
      ...commuteMapPatch,
      target: isCoordPoint(commuteMapPatch.target) ? commuteMapPatch.target : detail.commuteMap?.target,
      property: isCoordPoint(commuteMapPatch.property) ? commuteMapPatch.property : detail.commuteMap?.property,
      amenities: Array.isArray(commuteMapPatch.amenities)
        ? commuteMapPatch.amenities
        : detail.commuteMap?.amenities || [],
    }
  }
  return merged
}

/** 日期文案 */
export function formatDate(value) {
  if (!value) return '待补充'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value)
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

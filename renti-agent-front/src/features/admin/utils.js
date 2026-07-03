/**
 * 管理端通用工具：字段兜底双读（camelCase / snake_case）与格式化。
 */

/** 从对象中按顺序取第一个非 undefined/null 的字段值 */
export function pick(obj, ...keys) {
  if (!obj || typeof obj !== 'object') return undefined
  for (const key of keys) {
    const value = obj[key]
    if (value !== undefined && value !== null) return value
  }
  return undefined
}

/** camelCase -> snake_case */
export function toSnake(key) {
  return key.replace(/([A-Z])/g, '_$1').toLowerCase()
}

/** 双读：优先 camelCase，兜底 snake_case */
export function read(obj, camelKey, fallback) {
  const value = pick(obj, camelKey, toSnake(camelKey))
  return value === undefined ? fallback : value
}

/** 时间格式化：接受 ISO 字符串 / 秒或毫秒时间戳 */
export function formatTime(value) {
  if (value === undefined || value === null || value === '') return '—'
  let date
  if (typeof value === 'number') {
    date = new Date(value < 1e12 ? value * 1000 : value)
  } else {
    date = new Date(value)
  }
  if (Number.isNaN(date.getTime())) return String(value)
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 耗时格式化（毫秒） */
export function formatMs(value) {
  const ms = Number(value)
  if (!Number.isFinite(ms)) return '—'
  if (ms < 1000) return `${Math.round(ms)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

/** 从分页响应中解出列表（兼容 items/list/records/data/直接数组） */
export function extractItems(data) {
  if (Array.isArray(data)) return data
  if (!data || typeof data !== 'object') return []
  return (
    pick(data, 'items', 'list', 'records', 'rows', 'data', 'results', 'points', 'candidates', 'listings', 'users', 'logs', 'traces', 'audits', 'notifications') ?? []
  )
}

/** 从分页响应中解出总数 */
export function extractTotal(data, fallbackLen = 0) {
  if (!data || typeof data !== 'object') return fallbackLen
  const total = pick(data, 'total', 'count', 'totalCount', 'total_count')
  return typeof total === 'number' ? total : fallbackLen
}

/** 安全 JSON 序列化 */
export function toJson(value) {
  if (value === undefined || value === null) return ''
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2)
    } catch {
      return value
    }
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** 截断文本 */
export function truncate(text, max = 80) {
  const str = typeof text === 'string' ? text : toJson(text)
  if (!str) return '—'
  return str.length > max ? `${str.slice(0, max)}…` : str
}

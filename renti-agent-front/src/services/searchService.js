import apiClient from './apiClient.js'

/** 地图搜索 / 需求解析 / 推荐 API（城市工作台核心链路） */
export const searchService = {
  /** 地图选点 → 标准化地点对象 */
  resolvePlace: (place) => apiClient.post('/api/places/resolve', place),

  /** 地点文本 → 坐标（高德 geocode） */
  geocode: (payload) => apiClient.post('/api/locations/geocode', payload),

  /** 需求文本解析为结构化 Requirement */
  parseRequirements: (payload) => apiClient.post('/api/requirements/parse', payload),

  /** 基于 Requirement 的推荐 */
  searchRecommendations: (requirement) =>
    apiClient.post('/api/recommendations/search', { requirement }),

  /** 自然语言/地图点击 → 意图 + 周边房源推荐（规则链路） */
  mapIntent: (payload) => apiClient.post('/api/search/map-intent', payload),

  /** 仅解析定位目标，不搜索房源 */
  mapTarget: (payload) => apiClient.post('/api/search/map-target', payload),
}

/** AI Agent API（LangGraph 链路） */
export const agentService = {
  /** 租房搜索 agent */
  rentalSearch: (payload) => apiClient.post('/api/agent/rental-search', payload),

  /** 房源洞察 agent */
  propertyInsight: (payload) => apiClient.post('/api/agent/property-insight', payload),

  /** 房源问答会话 */
  listChatSessions: (listingId) =>
    apiClient.get('/api/agent/property-chat/sessions', { params: { listingId } }),
  createChatSession: (payload) => apiClient.post('/api/agent/property-chat/sessions', payload),
  sendChatMessage: (sessionId, payload) =>
    apiClient.post(`/api/agent/property-chat/sessions/${sessionId}/messages`, payload),
  clearChatSessions: (listingId) =>
    apiClient.delete('/api/agent/property-chat/sessions', { params: { listingId } }),
}

/** 房源详情 API */
export const listingService = {
  getDetail: (listingId) => apiClient.get(`/api/listings/${encodeURIComponent(listingId)}`),
  runDetailAnalysis: (listingId, payload = {}) =>
    apiClient.post(`/api/listings/${encodeURIComponent(listingId)}/detail-analysis`, payload),
  /** 外部图片走后端代理，规避防盗链 */
  proxyImageUrl: (url) => (url ? `/api/assets/listing-image?url=${encodeURIComponent(url)}` : ''),
}

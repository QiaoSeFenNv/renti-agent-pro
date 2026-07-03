import apiClient from './apiClient.js'

/** 城市与首页配置相关 API */
export const cityService = {
  /** 分页查询城市列表 */
  getCities: ({ query = '', page = 1, limit = 24 } = {}) =>
    apiClient.get('/api/cities', { params: { query, page, limit } }),

  /** 首页动态配置（heroBadge 等） */
  getHomeConfig: () => apiClient.get('/api/home/config'),
}

/** 邮箱订阅 API */
export const subscriptionService = {
  subscribe: (email) => apiClient.post('/api/home/subscribe', { email }),
  stats: () => apiClient.get('/api/home/subscribe/stats'),
}

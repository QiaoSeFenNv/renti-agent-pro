import apiClient from './apiClient.js'

/** 管理后台 API（全部走 renti_admin_session Cookie） */
export const adminService = {
  // 总览与用户
  getOverview: () => apiClient.get('/api/admin/overview'),
  getUsers: (limit = 50) => apiClient.get('/api/admin/users', { params: { limit } }),
  getUserDetail: (userId) => apiClient.get(`/api/admin/users/${userId}`),
  updateUserSettings: (userId, payload) =>
    apiClient.put(`/api/admin/users/${userId}/settings`, payload),
  updateUserConfig: (userId, payload) => apiClient.put(`/api/admin/users/${userId}/config`, payload),
  resetUserConfig: (userId) => apiClient.delete(`/api/admin/users/${userId}/config`),
  resetUserPassword: (userId, payload) =>
    apiClient.post(`/api/admin/users/${userId}/password`, payload),
  deleteUser: (userId) => apiClient.delete(`/api/admin/users/${userId}`),

  // 观测：日志 / trace / 交互 / 审计
  getLogs: (params) => apiClient.get('/api/admin/logs', { params }),
  getAgentTraces: (params) => apiClient.get('/api/admin/agent-traces', { params }),
  getAgentTraceDetail: (traceId) => apiClient.get(`/api/admin/agent-traces/${traceId}`),
  getUserInteractions: (params) => apiClient.get('/api/admin/user-interactions', { params }),
  getUserInteractionDetail: (id) => apiClient.get(`/api/admin/user-interactions/${id}`),
  getRetrievalAudits: (params) => apiClient.get('/api/admin/retrieval-audits', { params }),
  getRetrievalAuditDetail: (id) => apiClient.get(`/api/admin/retrieval-audits/${id}`),
  replayRetrievalAudit: (id) => apiClient.post(`/api/admin/retrieval-audits/${id}/replay`),

  // 公告
  getNotifications: () => apiClient.get('/api/admin/notifications'),
  createNotification: (payload) => apiClient.post('/api/admin/notifications', payload),
  updateNotification: (id, payload) => apiClient.put(`/api/admin/notifications/${id}`, payload),
  deleteNotification: (id) => apiClient.delete(`/api/admin/notifications/${id}`),

  // 平台配置
  getPlatformConfig: () => apiClient.get('/api/admin/config'),
  updatePlatformConfig: (payload) => apiClient.put('/api/admin/config', payload),
  getIntegrationsConfig: () => apiClient.get('/api/admin/system-integrations/config'),
  updateIntegrationsConfig: (payload) =>
    apiClient.put('/api/admin/system-integrations/config', payload),

  // 采集中心
  getIngestionOverview: () => apiClient.get('/api/admin/listing-ingestion/overview'),
  getCrawlerPlugins: () => apiClient.get('/api/admin/listing-ingestion/crawler-plugins'),
  runCrawlerPlugin: (pluginId, payload = {}) =>
    apiClient.post(`/api/admin/listing-ingestion/crawler-plugins/${pluginId}/run`, payload),
  getCrawlerSchedules: () => apiClient.get('/api/admin/listing-ingestion/crawler-schedules'),
  updateCrawlerSchedule: (pluginId, payload) =>
    apiClient.put(`/api/admin/listing-ingestion/crawler-schedules/${pluginId}`, payload),
  runDueCrawlerSchedules: () =>
    apiClient.post('/api/admin/listing-ingestion/crawler-schedules/run-due'),
  importListings: (payload) => apiClient.post('/api/admin/listing-ingestion/import', payload),
  getCandidates: (params) => apiClient.get('/api/admin/listing-ingestion/candidates', { params }),
  approveCandidate: (id) =>
    apiClient.post(`/api/admin/listing-ingestion/candidates/${id}/approve`),
  rejectCandidate: (id, payload = {}) =>
    apiClient.post(`/api/admin/listing-ingestion/candidates/${id}/reject`, payload),
  bulkApproveCandidates: (payload) =>
    apiClient.post('/api/admin/listing-ingestion/candidates/bulk-approve', payload),
  bulkRejectCandidates: (payload) =>
    apiClient.post('/api/admin/listing-ingestion/candidates/bulk-reject', payload),

  // 房源管理
  getListings: (params) => apiClient.get('/api/admin/listings', { params }),
  getListingDetail: (listingId) =>
    apiClient.get(`/api/admin/listings/${encodeURIComponent(listingId)}`),
  updateListing: (listingId, payload) =>
    apiClient.put(`/api/admin/listings/${encodeURIComponent(listingId)}`, payload),
  deleteListing: (listingId) =>
    apiClient.delete(`/api/admin/listings/${encodeURIComponent(listingId)}`),

  // RAG / 向量库
  getRagConfig: () => apiClient.get('/api/admin/rag/config'),
  updateRagConfig: (payload) => apiClient.put('/api/admin/rag/config', payload),
  getQdrantStatus: () => apiClient.get('/api/admin/rag/qdrant/status'),
  getQdrantPoints: (params) => apiClient.get('/api/admin/rag/qdrant/points', { params }),
  searchQdrant: (payload) => apiClient.post('/api/admin/rag/qdrant/search', payload),
  indexListings: (payload) => apiClient.post('/api/admin/rag/qdrant/index-listings', payload),

  // Neo4j 图谱
  getNeo4jConfig: () => apiClient.get('/api/admin/graph/neo4j/config'),
  updateNeo4jConfig: (payload) => apiClient.put('/api/admin/graph/neo4j/config', payload),
  getNeo4jStatus: () => apiClient.get('/api/admin/graph/neo4j/status'),
  queryNeo4j: (payload) => apiClient.post('/api/admin/graph/neo4j/query', payload),
  syncListingsToNeo4j: (payload) => apiClient.post('/api/admin/graph/neo4j/sync-listings', payload),
}

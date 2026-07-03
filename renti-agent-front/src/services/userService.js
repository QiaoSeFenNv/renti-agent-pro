import apiClient from './apiClient.js'

/** 登录用户的工作台数据 API */
export const userService = {
  changePassword: (payload) => apiClient.post('/api/auth/change-password', payload),
  clearPreferences: () => apiClient.delete('/api/user/preferences'),

  getSettings: () => apiClient.get('/api/user/settings'),
  updateSettings: (settings) => apiClient.put('/api/user/settings', settings),

  getNotifications: () => apiClient.get('/api/user/notifications'),
  readNotification: (id) => apiClient.post(`/api/user/notifications/${id}/read`),
  readAllNotifications: () => apiClient.post('/api/user/notifications/read-all'),

  getFavorites: () => apiClient.get('/api/user/favorites'),
  saveFavorite: (payload) => apiClient.post('/api/user/favorites', payload),
  removeFavorite: (listingId) =>
    apiClient.delete(`/api/user/favorites/${encodeURIComponent(listingId)}`),

  getHistory: () => apiClient.get('/api/user/history'),
  clearHistory: () => apiClient.delete('/api/user/history'),

  getImportedListings: (city) => apiClient.get('/api/user/imported-listings', { params: { city } }),
  saveImportedListing: (payload) => apiClient.post('/api/user/imported-listings', payload),
  clearImportedListings: (city) =>
    apiClient.delete('/api/user/imported-listings', { params: { city } }),
}

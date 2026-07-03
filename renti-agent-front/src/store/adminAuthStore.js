import { create } from 'zustand'

import apiClient from '../services/apiClient.js'

/**
 * 管理后台登录态 store，与用户端会话相互独立（后端使用不同 Cookie）。
 */
export const useAdminAuthStore = create((set, get) => ({
  status: 'idle',
  admin: null,

  fetchSession: async () => {
    set({ status: 'loading' })
    try {
      const data = await apiClient.get('/api/admin/session')
      if (data?.authenticated && data?.admin) {
        set({ status: 'authenticated', admin: data.admin })
      } else {
        set({ status: 'guest', admin: null })
      }
    } catch {
      set({ status: 'guest', admin: null })
    }
    return get().admin
  },

  login: async ({ username, password }) => {
    const data = await apiClient.post('/api/admin/login', { username, password })
    if (data?.ok && data?.admin) {
      set({ status: 'authenticated', admin: data.admin })
    }
    return data
  },

  logout: async () => {
    try {
      await apiClient.post('/api/admin/logout')
    } finally {
      set({ status: 'guest', admin: null })
    }
  },

  reset: () => set({ status: 'guest', admin: null }),
}))

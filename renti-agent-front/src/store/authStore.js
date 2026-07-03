import { create } from 'zustand'

import apiClient from '../services/apiClient.js'

/**
 * 用户登录态全局 store。
 *
 * status: 'idle' | 'loading' | 'authenticated' | 'guest'
 * 会话由后端 HttpOnly Cookie 维持，前端仅缓存用户信息。
 */
export const useAuthStore = create((set, get) => ({
  status: 'idle',
  user: null,

  /** 应用启动或登录态变化时拉取会话信息 */
  fetchSession: async () => {
    set({ status: 'loading' })
    try {
      const data = await apiClient.get('/api/auth/session')
      if (data?.authenticated && data?.user) {
        set({ status: 'authenticated', user: data.user })
      } else {
        set({ status: 'guest', user: null })
      }
    } catch {
      set({ status: 'guest', user: null })
    }
    return get().user
  },

  login: async ({ email, password }) => {
    const data = await apiClient.post('/api/auth/login', { email, password })
    if (data?.ok && data?.user) {
      set({ status: 'authenticated', user: data.user })
    }
    return data
  },

  register: async ({ email, password, nickname }) => {
    return apiClient.post('/api/auth/register', { email, password, nickname })
  },

  verifyEmail: async ({ email, code }) => {
    return apiClient.post('/api/auth/verify-email', { email, code })
  },

  logout: async () => {
    try {
      await apiClient.post('/api/auth/logout')
    } finally {
      set({ status: 'guest', user: null })
    }
  },

  /** 401 等场景下本地清除登录态（不调用后端） */
  reset: () => set({ status: 'guest', user: null }),
}))

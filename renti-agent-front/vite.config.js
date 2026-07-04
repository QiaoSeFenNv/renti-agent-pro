import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 开发环境将 /api 代理到 Spring Boot 后端，避免跨域配置分散。
export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
})

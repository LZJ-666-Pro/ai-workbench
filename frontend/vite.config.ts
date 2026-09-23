import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// 三个后端应用各自独立端口，开发期用 API 路径前缀区分代理：
//   /bank/api/*      → http://localhost:8081  (app-bank)
//   /knowledge/api/* → http://localhost:8082  (app-knowledge)
//   /interview/api/* → http://localhost:8083  (app-interview)
// 注意代理键必须收窄到 /xxx/api，否则会把 SPA 的页面路由 /bank 一并劫持去转发。
// 代理转发时剥掉前缀，同时天然免 CORS。
// Phase 4 部署时把这三条规则换成网关（Spring Cloud Gateway / Nginx）即可。
// target 用 127.0.0.1 而非 localhost：Windows 上 localhost 可能解析为
// IPv6 ::1，而 Spring Boot 默认只监听 IPv4，导致代理偶发 500。
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/bank/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
        rewrite: p => p.replace(/^\/bank/, ''),
      },
      '/knowledge/api': {
        target: 'http://127.0.0.1:8082',
        changeOrigin: true,
        rewrite: p => p.replace(/^\/knowledge/, ''),
      },
      '/interview/api': {
        target: 'http://127.0.0.1:8083',
        changeOrigin: true,
        rewrite: p => p.replace(/^\/interview/, ''),
      },
    },
  },
})

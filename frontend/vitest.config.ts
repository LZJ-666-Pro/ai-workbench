import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * 前端单测配置（与 vite.config.ts 分开，避免把测试配置带进构建产物）。
 *
 * 环境用 jsdom：被测代码依赖 localStorage（身份、会话缓存）与 DOM（组件挂载）。
 * 只跑 src 下的 *.spec.ts，构建产物与 node_modules 不参与。
 */
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.spec.ts'],
    // 组件与 API 逻辑都是纯前端行为，不需要真实后端
    unstubGlobals: true,
  },
})

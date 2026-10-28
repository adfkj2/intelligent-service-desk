import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    allowedHosts: true,
    // 浏览器只看到 5173 一个源，Session Cookie 因此是同源的；
    // 后端不需要配置 CORS，也不需要在 localStorage 里存任何 token。
    proxy: {
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: false
      }
    }
  },
  build: {
    // 构建产物直接进 Spring Boot 静态目录，最终仍是一个 jar
    outDir: '../src/main/resources/static',
    // 输出目录在 Vite root 之外时必须显式清空，否则残留旧 hash 文件会导致白屏
    emptyOutDir: true
  }
})

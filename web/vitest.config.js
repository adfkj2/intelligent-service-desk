import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

/**
 * 单测配置与 vite.config.js 分开维护的原因：
 * vite.config.js 的 build.outDir 指向 ../src/main/resources/static，
 * 被测试继承后既会误清后端静态目录，也会把「生产构建」的语义带进测试。
 */
export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['tests/setup.js'],
    include: ['tests/**/*.spec.js'],
    // 只统计业务代码：入口与常量没有分支可测，算进去只会稀释数字
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/**/*.{js,vue}'],
      exclude: ['src/main.js', 'src/constants/**']
    }
  }
})

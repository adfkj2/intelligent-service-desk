import { defineConfig, devices } from '@playwright/test'

/**
 * E2E 配置：打 docker-compose.prod.yml 起的 https 栈（经 Nginx）。
 *
 * - ignoreHTTPSErrors：本地是自签证书，浏览器告警不能变成用例失败；
 * - workers: 1：所有用例共用一个数据库，并发会互相抢工单状态；
 * - trace: retain-on-failure：失败时在 web/test-results 下留 trace.zip，CI 里可回放；
 * - baseURL 可用 E2E_BASE_URL 覆盖，方便打已部署的环境。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://localhost',
    ignoreHTTPSErrors: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }]
})

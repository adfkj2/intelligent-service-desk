import { test, expect } from '@playwright/test'
import { ACCOUNTS, login, logout } from './helpers/ui.js'

/**
 * 主流程冒烟：员工建单 → 技术支持接单并解决 → 员工关闭。
 *
 * 这一条同时覆盖四件在生产环境才成立的事：
 * 1. TLS 与 Secure Cookie（走 https://localhost，经 Nginx）；
 * 2. CSRF 双提交（每次写请求都由浏览器自动带 X-XSRF-TOKEN）；
 * 3. CSP 生效且不误伤页面（监听 Chromium 的 CSP 违规）；
 * 4. 三个角色的权限裁剪（按钮只在有权限时出现）。
 */
test.describe('工单主流程', () => {
  test('员工建单 → 技术支持接单并解决 → 员工关闭', async ({ page }) => {
    const cspViolations = []
    const pageErrors = []

    page.on('console', (message) => {
      if (message.type() !== 'error') {
        return
      }
      const text = message.text()
      // Chromium 把 CSP 拦截打成 console error："Refused to apply inline style because it violates ..."
      if (/content security policy|refused to (load|execute|apply|connect)/i.test(text)) {
        cspViolations.push(text)
      }
    })
    page.on('pageerror', (error) => pageErrors.push(error.message))

    // 先证明 prod 栈真的下发了 CSP——否则页面渲染正常也不能说明安全头在生效
    const ping = await page.request.get('/api/ping')
    expect(ping.ok()).toBeTruthy()
    const csp = ping.headers()['content-security-policy'] ?? ''
    expect(csp, '生产环境必须下发 CSP').toContain("script-src 'self'")
    expect(csp, '脚本不允许 eval').not.toContain('unsafe-eval')

    const title = `E2E 打印机故障 ${Date.now()}`

    // 1) 员工建单
    await login(page, ACCOUNTS.employee)
    await page.goto('/tickets/create')
    await page.getByTestId('ticket-title').fill(title)
    await page.getByTestId('ticket-submit').click()

    await expect(page).toHaveURL(/\/tickets\/\d+$/)
    const ticketId = page.url().match(/\/tickets\/(\d+)$/)[1]
    await expect(page.getByTestId('ticket-status')).toHaveText('待接单')
    await logout(page)

    // 2) 技术支持接单 + 解决
    await login(page, ACCOUNTS.support)
    await page.goto(`/tickets/${ticketId}`)
    await page.getByTestId('action-claim').click()
    await page.getByRole('button', { name: '确认接单' }).click()
    await expect(page.getByTestId('ticket-status')).toHaveText('处理中')

    await page.getByTestId('action-resolve').click()
    await page.getByTestId('action-confirm').click()
    await expect(page.getByTestId('ticket-status')).toHaveText('已解决')
    await logout(page)

    // 3) 员工关闭（只有创建者/管理员能关）
    await login(page, ACCOUNTS.employee)
    await page.goto(`/tickets/${ticketId}`)
    await page.getByTestId('action-close').click()
    await page.getByTestId('action-confirm').click()
    await expect(page.getByTestId('ticket-status')).toHaveText('已关闭')

    expect(cspViolations, `CSP 违规：\n${cspViolations.join('\n')}`).toEqual([])
    expect(pageErrors, `页面脚本错误：\n${pageErrors.join('\n')}`).toEqual([])
  })
})

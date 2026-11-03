import { test, expect } from '@playwright/test'

async function mockApi(page, role = 'ADMIN') {
  const initial = { id: 1, version: 1, name: '日常服务台', provider: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', keySuffix: 'abcd', currency: 'CNY', inputPrice: '2', cachedInputPrice: '0.2', outputPrice: '3', testStatus: 'PASSED', testedAt: '2026-11-03T10:00:00', archived: false }
  const state = { configurations: [initial], versions: { 1: [initial] }, saves: [], tests: [], activations: [], archives: [], usageQueries: [], unexpected: [], runtime: { mode: 'MANAGED', version: 1, configurationId: 1, configurationVersion: 1, provider: 'DEEPSEEK', model: 'deepseek-chat', keyConfigured: true, credentialsReady: true, message: '当前使用日常服务台。' } }
  const user = { id: 3, username: 'admin', fullName: '服务台管理员', role, mustChangePassword: false }
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.slice(4)
    const method = request.method()
    const body = method === 'GET' ? null : request.postDataJSON()
    let data
    if (path === '/auth/me') data = user
    else if (path === '/notifications/unread-count') data = { count: 0 }
    else if (path === '/notifications') data = []
    else if (path === '/tickets/overview') data = { counts: {}, queues: {}, supportWorkloads: [] }
    else if (path === '/tickets') data = { page: 1, size: 10, total: 0, items: [] }
    else if (path === '/ai/metrics') data = { answers: 0, feedbackCount: 0 }
    else if (path === '/admin/users') data = [user]
    else if (path === '/admin/ai/runtime' && method === 'GET') data = state.runtime
    else if (path === '/admin/ai/runtime' && method === 'PUT') {
      state.activations.push(body)
      const selected = state.versions[body.configurationId]?.find(item => item.version === body.configurationVersion)
      state.runtime = { ...state.runtime, ...body, version: state.runtime.version + 1, provider: selected?.provider || null, model: selected?.model || null, message: body.mode === 'DISABLED' ? '已停用模型服务，使用知识库降级建议。' : `当前使用${selected.name}。` }
      data = state.runtime
    } else if (path === '/admin/ai/configurations' && method === 'GET') data = state.configurations
    else if (path === '/admin/ai/configurations' && method === 'POST') {
      state.saves.push(body)
      const { apiKey, ...safe } = body
      const item = { ...safe, id: state.configurations.length + 1, version: 1, keySuffix: apiKey.slice(-4), testStatus: 'UNTESTED', archived: false }
      state.configurations.push(item)
      state.versions[item.id] = [item]
      data = item
    } else if (/^\/admin\/ai\/configurations\/\d+$/.test(path) && method === 'PUT') {
      state.saves.push(body)
      const id = Number(path.split('/')[4])
      const previous = state.configurations.find(item => item.id === id)
      const { apiKey, ...safe } = body
      const item = { ...safe, id, version: previous.version + 1, keySuffix: apiKey ? apiKey.slice(-4) : previous.keySuffix, testStatus: 'UNTESTED', archived: false }
      state.configurations = state.configurations.map(row => row.id === id ? item : row)
      state.versions[id].push(item)
      data = item
    } else if (/^\/admin\/ai\/configurations\/\d+\/versions$/.test(path)) data = state.versions[Number(path.split('/')[4])]
    else if (/^\/admin\/ai\/configurations\/\d+\/test$/.test(path)) {
      const id = Number(path.split('/')[4])
      state.tests.push({ id, ...body })
      const item = state.configurations.find(row => row.id === id)
      item.testStatus = 'PASSED'
      item.testedAt = '2026-11-03T10:30:00'
      data = item
    } else if (/^\/admin\/ai\/configurations\/\d+\/archive$/.test(path)) {
      const id = Number(path.split('/')[4])
      state.archives.push({ id, ...body })
      state.configurations.find(item => item.id === id).archived = true
      data = null
    } else if (/^\/admin\/ai\/configurations\/\d+\/balance(?:\/refresh)?$/.test(path)) data = { supported: true, available: true, stale: path.endsWith('/refresh'), checkedAt: '2026-11-03T10:00:00', attemptedAt: '2026-11-03T10:30:00', message: path.endsWith('/refresh') ? '刷新失败，请稍后重试。' : '供应商账户余额', consoleUrl: 'https://platform.deepseek.com/usage', balances: [{ currency: 'CNY', totalBalance: '50.6', grantedBalance: '10', toppedUpBalance: '40.6' }] }
    else if (path.startsWith('/admin/ai/usage/')) {
      state.usageQueries.push({ path, query: Object.fromEntries(url.searchParams) })
      if (path.endsWith('/summary')) data = { calls: 4, upstreamRequests: 7, failedCalls: 1, pendingCalls: 0, unknownUsageCalls: 1, inputTokens: 10200, cachedInputTokens: 2000, outputTokens: 800, costs: [{ currency: 'CNY', estimatedCost: '0.0184', unknownCostCalls: 1 }, { currency: 'USD', estimatedCost: '0.03', unknownCostCalls: 0 }] }
      else if (path.endsWith('/daily')) data = [{ date: '2026-11-02', calls: 1, upstreamRequests: 2, inputTokens: 2000, outputTokens: 200 }, { date: '2026-11-03', calls: 3, upstreamRequests: 5, inputTokens: 8200, outputTokens: 600 }]
      else if (path.endsWith('/users')) data = [{ userId: 3, fullName: '服务台管理员', username: 'admin', calls: 4, inputTokens: 10200, outputTokens: 800 }]
      else data = { page: 1, size: 10, total: 2, items: [{ id: 'one', userId: 3, fullName: '服务台管理员', purpose: 'CHAT', provider: 'DEEPSEEK', configurationName: '日常服务台', configurationVersion: 1, keySuffix: 'abcd', model: 'deepseek-chat', businessOutcome: 'SUCCESS', upstreamOutcome: 'SUCCESS', upstreamRequests: 2, inputTokens: 10200, cachedInputTokens: 2000, outputTokens: 800, usageComplete: true, estimatedCost: '0.0184', currency: 'CNY', createdAt: '2026-11-03T10:00:00' }, { id: 'two', userId: 3, fullName: '服务台管理员', purpose: 'CONNECTION_TEST', provider: 'OPENAI', configurationName: '备用模型', configurationVersion: 1, keySuffix: 'efgh', model: 'configured-model', businessOutcome: 'TIMEOUT', upstreamOutcome: 'ERROR', upstreamRequests: 1, inputTokens: null, cachedInputTokens: null, outputTokens: null, usageComplete: false, estimatedCost: null, currency: 'USD', createdAt: '2026-11-03T11:00:00' }] }
    } else { state.unexpected.push(`${method} ${path}`); await route.fulfill({ status: 404, json: { code: 40400, message: `未模拟 ${method} ${path}` } }); return }
    await route.fulfill({ json: { code: 0, message: 'OK', data } })
  })
  return state
}

test.describe('管理员 AI 配置与用量（模拟供应商）', () => {
  test('保存、测试与启用分开，更换 Key 不影响当前版本，停用后可归档', async ({ page }, testInfo) => {
    const state = await mockApi(page)
    await page.goto('/admin/ai')
    await expect(page.getByRole('heading', { name: 'AI 配置与用量' })).toBeVisible()
    await page.getByRole('button', { name: '新增配置', exact: true }).click()
    await page.getByLabel('配置名称', { exact: true }).fill('备用模型')
    await page.getByLabel('模型 API Key', { exact: true }).fill('sk-test-keep-only-in-input-efgh')
    await page.getByRole('button', { name: '保存待启用版本', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '新增模型服务配置' })).not.toBeVisible()
    expect(state.saves).toHaveLength(1)
    expect(state.activations).toHaveLength(0)
    const row = page.getByRole('row').filter({ hasText: '备用模型' }).first()
    await expect(row.getByRole('button', { name: '设为当前', exact: true })).toBeDisabled()
    await row.getByRole('button', { name: '测试连接', exact: true }).click()
    await expect(page.getByText(/可能产生少量 Token 费用/)).toBeVisible()
    await page.getByRole('button', { name: '开始测试', exact: true }).click()
    await expect(row.getByText('测试通过', { exact: true })).toBeVisible()
    expect(state.activations).toHaveLength(0)
    await row.getByRole('button', { name: '设为当前', exact: true }).click()
    await page.getByRole('button', { name: '确认切换', exact: true }).click()
    await expect(page.getByRole('region', { name: '当前 AI 服务' })).toContainText('备用模型')
    expect(state.activations.at(-1)).toMatchObject({ mode: 'MANAGED', configurationId: 2, configurationVersion: 1 })
    await row.getByRole('button', { name: '编辑', exact: true }).click()
    await expect(page.getByLabel('模型 API Key', { exact: true })).toHaveValue('')
    await page.getByLabel('模型 API Key', { exact: true }).fill('sk-new-key-last-wxyz')
    await page.getByRole('button', { name: '保存待启用版本', exact: true }).click()
    await expect(page.getByRole('dialog', { name: '编辑模型服务配置' })).not.toBeVisible()
    await expect(row).toContainText('正在使用版本 1')
    expect(state.runtime.configurationVersion).toBe(1)
    expect(await page.locator('body').textContent()).not.toContain('sk-new-key-last-wxyz')
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain('sk-')
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('admin-ai-config-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
    await page.screenshot({ path: testInfo.outputPath('admin-ai-config-mobile.png'), fullPage: true })
    await page.getByRole('button', { name: '停用 AI', exact: true }).click()
    await page.getByRole('button', { name: '确认停用', exact: true }).click()
    await expect(page.getByRole('heading', { name: 'AI 已停用' })).toBeVisible()
    expect(state.activations.at(-1).mode).toBe('DISABLED')
    await row.getByRole('button', { name: '归档', exact: true }).click()
    await page.getByRole('button', { name: '确认归档', exact: true }).click()
    await expect(row).not.toBeVisible()
    expect(state.archives).toEqual([{ id: 2, version: 2 }])
    expect(state.unexpected).toEqual([])
  })

  test('用量区分未知与零、分币种显示费用，余额刷新失败保留旧结果', async ({ page }, testInfo) => {
    const state = await mockApi(page)
    await page.goto('/admin/ai')
    await page.getByRole('button', { name: '查看余额', exact: true }).click()
    const balanceDialog = page.getByRole('dialog', { name: '日常服务台 · 官方账户余额' })
    await expect(balanceDialog.getByText('50.60', { exact: true })).toBeVisible()
    await expect(balanceDialog).toContainText('多个 Key 的余额不能相加')
    await balanceDialog.getByRole('button', { name: '刷新余额', exact: true }).click()
    await expect(balanceDialog).toContainText('当前显示上次查询结果，可能已过期')
    await expect(balanceDialog.getByText('50.60', { exact: true })).toBeVisible()
    await balanceDialog.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByRole('tab', { name: '用量与费用', exact: true }).click()
    await expect(page.getByTestId('usage-call-count')).toHaveText('4')
    await expect(page.getByText('CNY 0.0184', { exact: true }).first()).toBeVisible()
    await expect(page.getByText('USD 0.03', { exact: true })).toBeVisible()
    await expect(page.getByText('用量未知或不完整', { exact: true })).toBeVisible()
    await expect(page.getByText('未知 / 未配置', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '近 7 天', exact: true }).click()
    await expect.poll(() => state.usageQueries.filter(item => item.path.endsWith('/summary')).length).toBe(2)
    const query = state.usageQueries.filter(item => item.path.endsWith('/summary')).at(-1).query
    expect((Date.parse(query.to) - Date.parse(query.from)) / 86400000).toBe(6)
    await page.screenshot({ path: testInfo.outputPath('admin-ai-usage-desktop.png'), fullPage: true })
    await page.getByRole('heading', { name: '费用估算', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('admin-ai-usage-summary-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
    await page.screenshot({ path: testInfo.outputPath('admin-ai-usage-mobile.png'), fullPage: true })
    await page.getByRole('heading', { name: '调用明细', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath('admin-ai-usage-details-mobile.png'), fullPage: true })
    expect(state.unexpected).toEqual([])
  })

  for (const role of ['EMPLOYEE', 'SUPPORT']) {
    test(`${role} 不显示管理入口，直接访问会返回工作台`, async ({ page }) => {
      const state = await mockApi(page, role)
      await page.goto('/admin/ai')
      await expect(page).toHaveURL(/\/$/)
      await expect(page.getByRole('link', { name: 'AI 配置与用量', exact: true })).toHaveCount(0)
      expect(state.usageQueries).toHaveLength(0)
      expect(state.unexpected).toEqual([])
    })
  }
})

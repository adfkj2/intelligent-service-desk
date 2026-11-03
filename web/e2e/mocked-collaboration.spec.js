import { test, expect } from '@playwright/test'

const ticket = { id: 7, ticketNo: 'T20261103-1007', title: 'VPN 连接超时，远程办公受影响', description: '今天上午 9 点开始，VPN 提示连接超时。家中网络正常，重启客户端后仍然无法连接。', category: 'IT_FAULT', priority: 'HIGH', status: 'IN_PROGRESS', creatorId: 1, creatorName: '陈晓', assigneeId: 2, assigneeName: '李工', createdAt: '2026-11-03T09:10:00', updatedAt: '2026-11-03T10:00:00', responseDueAt: '2026-11-03T10:10:00', resolutionDueAt: '2026-11-03T17:10:00', firstResponseAt: '2026-11-03T09:20:00', reopenCount: 0 }
const reference = { id: 6, title: '公司 VPN 连接故障排查', excerpt: '先核对客户端版本，再检查连接配置与错误提示。' }

async function mockApi(page, role = 'SUPPORT') {
  const state = { creates: [], replies: [{ id: 1, ticketId: 7, authorId: 1, authorName: '陈晓', content: '补充：同组两位同事可以正常连接。', visibility: 'PUBLIC', createdAt: '2026-11-03T09:18:00' }], draftVersion: 2, published: false, patchVersions: [], publishVersions: [], unexpected: [] }
  const user = { id: role === 'EMPLOYEE' ? 1 : 2, username: role === 'EMPLOYEE' ? 'chenxiao' : 'support', fullName: role === 'EMPLOYEE' ? '陈晓' : '李工', role, mustChangePassword: false }
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname.slice(4)
    const method = request.method()
    const body = method === 'GET' ? null : request.postDataJSON()
    let data
    if (path === '/auth/me') data = user
    else if (path === '/notifications/unread-count') data = { count: 0 }
    else if (path === '/notifications') data = []
    else if (path === '/tickets/overview') data = { counts: { OPEN: 3, IN_PROGRESS: 5, WAITING_CUSTOMER: 2, RESOLVED: 1, CLOSED: 8 }, queues: { MINE: 5, UNASSIGNED: 3, DUE_SOON: 2, OVERDUE: 1, WAITING_ME: 2, CONFIRM: 1 }, averageResponseSeconds: 900, averageResolutionSeconds: 7200, averageFillDurationSeconds: 105, reopenRate: 0.08, supportWorkloads: [{ userId: 2, name: '李工', activeTickets: 5, skills: ['IT_FAULT'] }] }
    else if (path === '/tickets/support-options') data = [{ userId: 2, name: '李工', activeTickets: 5, skills: ['IT_FAULT'] }]
    else if (path === '/tickets' && method === 'GET') data = { page: 1, size: 10, total: 1, items: [ticket] }
    else if (path === '/tickets' && method === 'POST') { state.creates.push(body); data = { ...ticket, ...body, id: 9, status: 'OPEN' } }
    else if (/^\/tickets\/\d+$/.test(path)) data = { ...ticket, id: Number(path.split('/')[2]) }
    else if (/^\/tickets\/\d+\/operations$/.test(path)) data = [{ id: 1, action: 'CREATE', operatorId: 1, operatorName: '陈晓', toStatus: 'OPEN', createdAt: '2026-11-03T09:10:00' }, { id: 2, action: 'CLAIM', operatorId: 2, operatorName: '李工', toStatus: 'IN_PROGRESS', createdAt: '2026-11-03T09:15:00' }]
    else if (/^\/tickets\/\d+\/replies$/.test(path) && method === 'POST') { state.replies.push({ ...body, id: state.replies.length + 1, authorId: user.id, authorName: user.fullName, createdAt: '2026-11-03T10:15:00' }); data = state.replies.at(-1) }
    else if (/^\/tickets\/\d+\/replies$/.test(path)) data = state.replies.filter(reply => role !== 'EMPLOYEE' || reply.visibility === 'PUBLIC')
    else if (/^\/tickets\/\d+\/attachments$/.test(path)) data = [{ id: 2, fileName: 'VPN 错误提示.png', size: 23040, visibility: 'PUBLIC' }]
    else if (path === '/ai/draft') data = { title: 'VPN 无法连接', description: body.description, category: 'IT_FAULT', priority: 'MEDIUM', missingInformation: ['什么时候开始出现？', '其他同事是否也受到影响？'], steps: ['记录客户端显示的错误信息', '确认本地网络可以访问其他网站'], references: [reference], source: 'LLM', answerId: 30, conversationId: 'draft-1' }
    else if (path === '/ai/chat') data = { answer: '当前工单由李工处理。根据现有描述，建议先核对 VPN 客户端版本，并记录完整错误提示；目前资料不足以确定故障原因。', source: 'LLM', relatedTitles: [reference.title], references: [reference], answerId: 31, conversationId: 'chat-7' }
    else if (path === '/ai/metrics') data = { answers: 20, feedbackCount: 12, helpfulCount: 9, resolvedCount: 7, adoptedCount: 8, suggestionCount: 10, helpfulFeedbackCount: 10, suggestionAdoptedCount: 8 }
    else if (/^\/ai\/answers\/\d+\/feedback$/.test(path)) data = null
    else if (path === '/kb/categories') data = [{ id: 3, name: '网络与远程办公' }]
    else if (path === '/kb/items') data = { page: 1, size: 10, total: 1, items: [{ id: 6, title: reference.title, categoryName: '网络与远程办公', tags: 'VPN,网络', createdBy: 2, updatedAt: '2026-11-03T10:00:00', environment: 'Windows 11 / 企业 VPN' }] }
    else if (path === '/kb/items/6') data = { id: 6, title: reference.title, content: '1. 检查客户端版本与网络连接。\n2. 记录错误代码。\n3. 核对连接配置，并联系技术支持。', categoryId: 3, categoryName: '网络与远程办公', tags: 'VPN,网络', createdBy: 2, environment: 'Windows 11 / 企业 VPN', updatedAt: '2026-11-03T10:00:00' }
    else if (path === '/kb/items/6/feedback') data = { helpful: 5, unhelpful: 1, myFeedback: null }
    else if (path === '/kb/drafts') data = [{ id: 4, ticketId: 7, title: 'VPN 客户端配置导致连接超时的处理方法', content: '适用情况：VPN 客户端提示连接超时。\n排查步骤：核对版本，检查连接配置。\n解决方法：使用已验证的连接配置并重新连接。', categoryId: 3, tags: 'VPN,客户端', environment: 'Windows 11 / 企业 VPN', reviewDueAt: '2027-02-03T10:00:00', status: state.published ? 'PUBLISHED' : 'DRAFT', createdBy: 2, source: 'LLM', version: state.draftVersion }]
    else if (path === '/kb/drafts/4' && method === 'PATCH') { state.patchVersions.push(body.version); state.draftVersion++; data = { ...body, id: 4, ticketId: 7, status: 'DRAFT', version: state.draftVersion } }
    else if (path === '/kb/drafts/4/publish') { state.publishVersions.push(body.version); state.published = true; data = { id: 6, title: reference.title } }
    else { state.unexpected.push(`${method} ${path}`); await route.fulfill({ status: 404, json: { code: 40400, message: `未模拟 ${method} ${path}` } }); return }
    await route.fulfill({ json: { code: 0, message: 'OK', data } })
  })
  return state
}

test.describe('新增工单与 AI 界面浏览器冒烟（模拟 API）', () => {
  test('员工生成并采用草稿后保持未提交，桌面和窄屏可以完成核对', async ({ page }, testInfo) => {
    const state = await mockApi(page, 'EMPLOYEE')
    await page.goto('/tickets/create')
    await page.getByLabel('自然语言描述问题').fill('VPN 连不上，上午开始提示连接超时')
    await page.getByTestId('generate-draft').click()
    await expect(page.getByRole('heading', { name: 'VPN 无法连接' })).toBeVisible()
    expect(state.creates).toHaveLength(0)
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.getByTestId('apply-draft').click()
    await expect(page.getByTestId('ticket-title')).toHaveValue('VPN 无法连接')
    expect(state.creates).toHaveLength(0)
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.locator('main').evaluate(element => { element.scrollTop = 0 })
    await page.screenshot({ path: testInfo.outputPath('create-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
    await page.locator('main').evaluate(element => { element.scrollTop = 0 })
    await page.screenshot({ path: testInfo.outputPath('create-mobile.png'), fullPage: true })
    await page.getByTestId('ticket-title').fill('VPN 连接超时，请协助排查')
    await page.getByTestId('ticket-submit').click()
    await expect(page).toHaveURL(/\/tickets\/9$/)
    expect(state.creates).toHaveLength(1)
    expect(state.creates[0].title).toBe('VPN 连接超时，请协助排查')
    expect(state.unexpected).toEqual([])
  })

  test('技术支持公开回复和内部备注使用不同可见性，AI 引用能打开原文', async ({ page }, testInfo) => {
    const state = await mockApi(page)
    await page.goto('/')
    await expect(page.getByText(/建议采纳 80%/)).toBeVisible()
    await page.goto('/tickets/7')
    await page.getByLabel('工单回复').fill('请提供 VPN 客户端版本号和错误截图。')
    await page.getByTestId('send-reply').click()
    await expect(page.getByText('请提供 VPN 客户端版本号和错误截图。', { exact: true })).toBeVisible()
    expect(state.replies.at(-1).visibility).toBe('PUBLIC')
    await page.locator('label.el-radio-button').filter({ hasText: '内部备注' }).click()
    await expect(page.getByRole('radio', { name: '内部备注', exact: true })).toBeChecked()
    await page.getByLabel('工单回复').fill('已检查网关监控，未发现集体故障。')
    await page.getByTestId('send-reply').click()
    await expect(page.getByText('已检查网关监控，未发现集体故障。', { exact: true })).toBeVisible()
    expect(state.replies.at(-1).visibility).toBe('INTERNAL')
    await expect(page.locator('.el-message')).toHaveCount(0)
    await page.getByRole('heading', { name: '沟通与补充信息' }).locator('..').screenshot({ path: testInfo.outputPath('detail-replies.png') })
    await page.locator('main').evaluate(element => { element.scrollTop = 0 })
    await page.screenshot({ path: testInfo.outputPath('detail-desktop.png'), fullPage: true })
    await page.getByRole('button', { name: '问问当前工单助手' }).click()
    await page.getByLabel('向 AI 提问').fill('下一步应该如何排查？')
    await page.getByRole('button', { name: '发送', exact: true }).click()
    await expect(page.getByRole('link', { name: /公司 VPN 连接故障排查/ })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('ai-citations-desktop.png'), fullPage: true })
    await page.getByRole('link', { name: /公司 VPN 连接故障排查/ }).click()
    await expect(page).toHaveURL(/\/kb\?itemId=6$/)
    await expect(page.getByRole('dialog', { name: '公司 VPN 连接故障排查' })).toBeVisible()
    await expect(page.getByText('1. 检查客户端版本与网络连接。', { exact: false })).toBeVisible()
    expect(state.unexpected).toEqual([])
  })

  test('知识审核保存后使用最新版本发布，发布后退出审核抽屉', async ({ page }, testInfo) => {
    const state = await mockApi(page)
    await page.goto('/kb?draftId=4')
    await expect(page.getByRole('dialog', { name: '审核知识草稿' })).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('knowledge-review-desktop.png'), fullPage: true })
    await page.getByRole('button', { name: '审核并发布', exact: true }).click()
    await page.getByRole('button', { name: '确认发布', exact: true }).click()
    await expect(page.getByText('知识已发布', { exact: true })).toBeVisible()
    expect(state.patchVersions).toEqual([2])
    expect(state.publishVersions).toEqual([3])
    await expect(page.getByRole('dialog', { name: '审核知识草稿' })).not.toBeVisible()
    expect(state.unexpected).toEqual([])
  })

  test('窄屏工单详情和角色工作台不产生整页横向滚动', async ({ page }, testInfo) => {
    const state = await mockApi(page, 'EMPLOYEE')
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/tickets/7')
    await expect(page.getByRole('heading', { name: ticket.title })).toBeVisible()
    await expect(page.getByRole('radio', { name: '内部备注' })).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
    await page.screenshot({ path: testInfo.outputPath('detail-mobile.png'), fullPage: true })
    await page.goto('/')
    await expect(page.getByRole('button', { name: /待我补充/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /待我确认/ })).toBeVisible()
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(391)
    await page.screenshot({ path: testInfo.outputPath('workbench-mobile.png'), fullPage: true })
    expect(state.unexpected).toEqual([])
  })
})

/**
 * README 截图脚本（仅操作 isd-demo 隔离环境）。
 *
 *   node web/e2e/demo/seed.mjs       # 先重建演示数据
 *   node web/e2e/demo/screenshots.mjs
 *
 * 输出：docs/screenshots/*.png，统一 1440×1000、deviceScaleFactor=1。
 * 不使用真实密钥、内部地址或个人信息；AI 用量为演示台账（见 docs/screenshots/README.md）。
 */
import { chromium } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '../../..')
const outputDir = path.join(repoRoot, 'docs', 'screenshots')
const stateFile = path.join(here, '.demo-state.json')

const BASE_URL = process.env.ISD_DEMO_BASE_URL ?? 'http://localhost:18081'
const parsed = new URL(BASE_URL)
if (!['localhost', '127.0.0.1'].includes(parsed.hostname) || parsed.port !== '18081') {
  throw new Error(`拒绝执行：截图脚本只允许 http://localhost:18081（当前 ${BASE_URL}）`)
}
if (!fs.existsSync(stateFile)) {
  throw new Error('缺少演示数据状态文件，请先执行 node web/e2e/demo/seed.mjs')
}

const state = JSON.parse(fs.readFileSync(stateFile, 'utf8'))
const ACCOUNTS = {
  emp: { username: 'demo_emp', password: 'employee123' },
  sup: { username: 'demo_sup', password: 'support123' },
  admin: { username: 'demo_admin', password: 'admin12345' }
}

fs.mkdirSync(outputDir, { recursive: true })

const browser = await chromium.launch()

async function shot(name, account, prepare) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    ignoreHTTPSErrors: true,
    reducedMotion: 'reduce'
  })
  const page = await context.newPage()
  await page.goto(`${BASE_URL}/login`)
  await page.getByTestId('login-username').fill(account.username)
  await page.getByTestId('login-password').fill(account.password)
  await page.getByTestId('login-submit').click()
  await page.waitForURL((url) => url.pathname === '/')
  await page.getByRole('link', { name: '工作台' }).waitFor()
  await prepare(page)
  // 登录成功的 toast 会挡住主图，等它自然消失再截图
  await page.locator('.el-message').first().waitFor({ state: 'hidden', timeout: 6000 }).catch(() => {})
  await page.waitForTimeout(400)
  await page.screenshot({ path: path.join(outputDir, `${name}.png`) })
  await context.close()
  console.log(`captured ${name}.png`)
}

await shot('workbench', ACCOUNTS.admin, async (page) => {
  await page.getByRole('heading', { name: '工作台' }).waitFor()
  await page.getByRole('heading', { name: '人员负荷与技能' }).waitFor()
  await page.waitForTimeout(800)
})

await shot('ticket-create', ACCOUNTS.emp, async (page) => {
  await page.goto(`${BASE_URL}/tickets/create`)
  await page.getByRole('textbox', { name: '自然语言描述问题' }).fill(
    '今天上午开始 VPN 一直提示连接超时，家里网络正常，重启客户端后仍然无法连接，影响远程访问内网。'
  )
  await page.getByTestId('generate-draft').click()
  await page.getByText('采用草稿并核对').waitFor()
  await page.getByTestId('apply-draft').click()
  await page.getByTestId('ticket-title').waitFor()
  await page.getByText('再补充这些信息').scrollIntoViewIfNeeded()
})

await shot('ticket-detail', ACCOUNTS.sup, async (page) => {
  await page.goto(`${BASE_URL}/tickets/${state.tickets.vpn}`)
  await page.getByRole('heading', { name: '公司 VPN 连续超时，无法访问内网' }).waitFor()
  await page.getByText('沟通与补充信息').scrollIntoViewIfNeeded()
  await page.mouse.wheel(0, 110)
})

await shot('knowledge-review', ACCOUNTS.admin, async (page) => {
  await page.goto(`${BASE_URL}/kb`)
  await page.getByRole('heading', { name: '知识库' }).waitFor()
  await page.getByRole('button', { name: '审核草稿' }).first().click()
  await page.getByRole('heading', { name: '审核知识草稿', exact: true }).waitFor()
  await page.waitForTimeout(400)
})

await shot('ai-config', ACCOUNTS.admin, async (page) => {
  await page.goto(`${BASE_URL}/admin/ai`)
  await page.getByRole('heading', { name: 'AI 配置与用量' }).waitFor()
  await page.getByText('DeepSeek 服务台主通道').waitFor()
})

await shot('ai-usage', ACCOUNTS.admin, async (page) => {
  await page.goto(`${BASE_URL}/admin/ai`)
  await page.getByRole('tab', { name: '用量与费用' }).click()
  await page.getByTestId('usage-call-count').waitFor()
  await page.getByText('每日调用趋势').scrollIntoViewIfNeeded()
  await page.mouse.wheel(0, 120)
})

await browser.close()
console.log(`screenshots -> ${outputDir}`)

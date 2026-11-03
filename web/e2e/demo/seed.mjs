/**
 * README 截图专用演示数据初始化（仅操作 isd-demo 隔离环境）。
 *
 *   docker compose -f docker-compose.demo.yml up -d --build
 *   node web/e2e/demo/seed.mjs
 *
 * 脚本默认 **重建** isd-demo 的数据卷（down -v + up -d --build），保证每次
 * 截图的数据一致、可重复。只有 host 为 localhost/127.0.0.1 且端口 18081 时
 * 才允许执行，避免误伤日常环境。
 *
 * 账号（与 deploy/demo-seed.sql 一致）：
 *   demo_emp / demo_emp2      employee123
 *   demo_sup / demo_sup2      support123
 *   demo_admin                admin12345
 */
import { request } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '../../..')
const composeFile = path.join(repoRoot, 'docker-compose.demo.yml')
const stateFile = path.join(here, '.demo-state.json')

const BASE_URL = process.env.ISD_DEMO_BASE_URL ?? 'http://localhost:18081'

const parsed = new URL(BASE_URL)
if (!['localhost', '127.0.0.1'].includes(parsed.hostname) || parsed.port !== '18081') {
  throw new Error(`拒绝执行：演示脚本只允许 http://localhost:18081（当前 ${BASE_URL}）`)
}

const HAShes = {
  employee: '$2a$10$HubxZz4BHgRFfYnuJSmE0OOBPzQkXLSjARyUkZmqw.UcPVBfcVhPG',
  support: '$2a$10$XWGBYDz4ojVaH3QjKSWndeMjAYHV6oM1T7IBs7RLIGr7iShvB8kO.',
  admin: '$2a$10$3G4yB.gIoGckORTlvj1yIeGSPU5h19969K6PiO4wsbM.0OylMogSC'
}

const ACCOUNTS = {
  emp: { username: 'demo_emp', password: 'employee123' },
  emp2: { username: 'demo_emp2', password: 'employee123' },
  sup: { username: 'demo_sup', password: 'support123' },
  sup2: { username: 'demo_sup2', password: 'support123' },
  admin: { username: 'demo_admin', password: 'admin12345' }
}

function compose(args, input) {
  return execFileSync('docker', ['compose', '-f', composeFile, ...args], {
    cwd: repoRoot,
    input,
    encoding: 'utf8',
    stdio: input === undefined ? 'inherit' : ['pipe', 'pipe', 'pipe']
  })
}

function sql(statement) {
  return compose(
    ['exec', '-T', 'mysql', 'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot isd'],
    statement
  )
}

async function waitForApp(timeoutMs = 240_000) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${BASE_URL}/api/ping`)
      if (response.ok) {
        return
      }
    } catch {
      // 应用还没起来，继续等
    }
    await new Promise((resolve) => setTimeout(resolve, 3000))
  }
  throw new Error(`演示应用在 ${timeoutMs / 1000}s 内未就绪：${BASE_URL}`)
}

async function csrfToken(context) {
  const state = await context.storageState()
  const cookie = state.cookies.find((item) => item.name === 'XSRF-TOKEN')
  if (!cookie) {
    throw new Error('后端没有下发 XSRF-TOKEN，检查 CSRF 配置')
  }
  return cookie.value
}

async function login(account) {
  const context = await request.newContext({ baseURL: BASE_URL })
  await context.get('/api/ping')
  let session = { context, token: await csrfToken(context) }
  const response = await context.post('/api/auth/login', {
    data: account,
    headers: { 'X-XSRF-TOKEN': session.token }
  })
  if (!response.ok()) {
    throw new Error(`登录 ${account.username} 失败：${response.status()} ${await response.text()}`)
  }
  session = { context, token: await csrfToken(context) }
  return session
}

async function call(session, method, url, data, headers = {}) {
  const response = await session.context.fetch(url, {
    method,
    data,
    headers: { 'X-XSRF-TOKEN': session.token, ...headers }
  })
  const text = await response.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    throw new Error(`${method} ${url} 返回非 JSON：${text.slice(0, 300)}`)
  }
  if (!response.ok() || (body && body.code !== 0)) {
    throw new Error(`${method} ${url} -> ${response.status()} ${text.slice(0, 500)}`)
  }
  return body?.data
}

function futureDate(months) {
  const date = new Date()
  date.setMonth(date.getMonth() + months)
  return date.toISOString().slice(0, 19)
}

async function main() {
  console.log('重建 isd-demo 隔离环境（数据卷会清空）...')
  try {
    compose(['down', '-v'])
  } catch {
    // 首次执行没有容器，忽略
  }
  compose(['up', '-d', '--build'])
  await waitForApp()
  console.log('应用已就绪，开始写入演示数据...')

  sql(`
    SET NAMES utf8mb4;
    INSERT INTO sys_user (username, password_hash, full_name, role, status, must_change_password, version)
    VALUES
      ('demo_emp',  '${HAShes.employee}', '陈晓', 'EMPLOYEE', 1, 0, 0),
      ('demo_emp2', '${HAShes.employee}', '李然', 'EMPLOYEE', 1, 0, 0),
      ('demo_sup',  '${HAShes.support}',  '周宁', 'SUPPORT',  1, 0, 0),
      ('demo_sup2', '${HAShes.support}',  '赵敏', 'SUPPORT',  1, 0, 0),
      ('demo_admin','${HAShes.admin}',    '王珂', 'ADMIN',    1, 0, 0)
    ON DUPLICATE KEY UPDATE password_hash=VALUES(password_hash), full_name=VALUES(full_name),
      role=VALUES(role), status=1, must_change_password=0;

    INSERT INTO kb_category (name, description) VALUES
      ('网络与远程办公', 'VPN、Wi-Fi、内网访问与远程桌面'),
      ('账号与权限', '账号开通、密码重置、共享盘与系统权限'),
      ('设备与外设', '电脑、打印机、显示器、会议室设备'),
      ('邮件与协同', 'Outlook、企业邮箱、日历与协作工具')
    ON DUPLICATE KEY UPDATE description=VALUES(description);
  `)

  const emp = await login(ACCOUNTS.emp)
  const emp2 = await login(ACCOUNTS.emp2)
  const sup = await login(ACCOUNTS.sup)
  const sup2 = await login(ACCOUNTS.sup2)
  const admin = await login(ACCOUNTS.admin)

  const users = await call(admin, 'GET', '/api/admin/users')
  const userId = Object.fromEntries(users.map((user) => [user.username, user.id]))

  await call(sup, 'PUT', '/api/tickets/support-profile', { skills: ['IT_FAULT', 'DEVICE'] })
  await call(sup2, 'PUT', '/api/tickets/support-profile', { skills: ['PERMISSION', 'IT_FAULT'] })

  const categories = await call(admin, 'GET', '/api/kb/categories')
  const categoryId = Object.fromEntries(categories.map((item) => [item.name, item.id]))

  async function createTicket(session, key, payload) {
    return call(session, 'POST', '/api/tickets', payload, { 'Idempotency-Key': key })
  }

  // 1) 主截图工单：VPN 超时，处理中，有公开回复、内部备注和附件
  const vpn = await createTicket(emp, 'demo-vpn-timeout', {
    title: '公司 VPN 连续超时，无法访问内网',
    description:
      '今天上午开始 VPN 一直提示连接超时，家里网络正常，重启客户端后仍然无法连接，影响远程访问内网。',
    category: 'IT_FAULT',
    priority: 'HIGH',
    fillDurationSeconds: 48
  })
  await call(sup, 'POST', `/api/tickets/${vpn.id}/claim`, {})
  await call(sup, 'POST', `/api/tickets/${vpn.id}/replies`, {
    content: '已收到，先确认一下：客户端版本和报错截图方便发一下吗？我这边同步检查接入网关。',
    visibility: 'PUBLIC'
  })
  await call(sup, 'POST', `/api/tickets/${vpn.id}/replies`, {
    content: '疑似 443 端口被本地安全软件拦截，待用户回传错误码后走备用接入点。',
    visibility: 'INTERNAL'
  })
  const attachment = await sup.context.post(`/api/tickets/${vpn.id}/attachments`, {
    headers: { 'X-XSRF-TOKEN': sup.token },
    multipart: {
      visibility: 'PUBLIC',
      file: {
        name: 'VPN-连接超时提示.txt',
        mimeType: 'text/plain',
        buffer: Buffer.from(
          '[演示数据] VPN 客户端提示\n时间：09:42\n错误：Connection timed out (Error 10060)\n目标：vpn.example.com:443\n已尝试：重启客户端、切换网络\n',
          'utf8'
        )
      }
    }
  })
  if (!attachment.ok()) {
    throw new Error(`附件上传失败：${attachment.status()} ${await attachment.text()}`)
  }

  // 2) 打印机：已解决，作为知识草稿来源（保留 DRAFT 供「知识审核」截图）
  const printer = await createTicket(emp2, 'demo-printer-jam', {
    title: '3 楼打印机卡纸后无法打印',
    description: '取纸后面板仍提示卡纸，重启打印机无效，同楼层同事也无法打印。',
    category: 'DEVICE',
    priority: 'MEDIUM'
  })
  await call(sup2, 'POST', `/api/tickets/${printer.id}/claim`, {})
  await call(sup2, 'POST', `/api/tickets/${printer.id}/replies`, {
    content: '已清理定影单元残留纸屑并复位传感器，连续打印 20 页正常，请再试一次。',
    visibility: 'PUBLIC'
  })
  await call(sup2, 'PATCH', `/api/tickets/${printer.id}/status`, {
    action: 'RESOLVE',
    comment: '清理卡纸传感器并做 20 页连续打印验证'
  })

  // 3) 待接单：权限申请
  await createTicket(emp, 'demo-share-permission', {
    title: '申请开通财务共享盘权限',
    description: '需要访问 \\\\fileserver\\finance\\2026 预算目录，只读即可，用于季度对账。',
    category: 'PERMISSION',
    priority: 'MEDIUM'
  })

  // 4) 等待用户补充：Outlook 收信异常
  const outlook = await createTicket(emp, 'demo-outlook-mail', {
    title: 'Outlook 收不到外部邮件',
    description: '内部邮件正常，外部供应商的邮件从昨天下午开始收不到，垃圾邮件里也没有。',
    category: 'IT_FAULT',
    priority: 'HIGH'
  })
  await call(sup, 'POST', `/api/tickets/${outlook.id}/claim`, {})
  await call(sup, 'PATCH', `/api/tickets/${outlook.id}/status`, {
    action: 'REQUEST_INFO',
    comment: '请提供发件人域名和最近一封退信截图，我这边先查邮件网关日志。'
  })

  // 5) 已关闭：会议室投屏无声，发布一条知识
  const projector = await createTicket(emp2, 'demo-projector-audio', {
    title: '会议室投屏没有声音',
    description: 'HDMI 投屏后画面正常，但会议室音箱没有声音，换了接口也一样。',
    category: 'DEVICE',
    priority: 'LOW'
  })
  await call(sup, 'POST', `/api/tickets/${projector.id}/claim`, {})
  await call(sup, 'POST', `/api/tickets/${projector.id}/replies`, {
    content: '已把系统默认播放设备切到 HDMI，并重启音频服务，现场测试声音正常。',
    visibility: 'PUBLIC'
  })
  await call(sup, 'PATCH', `/api/tickets/${projector.id}/status`, {
    action: 'RESOLVE',
    comment: '切换默认播放设备为 HDMI 并重启音频服务'
  })
  await call(emp2, 'PATCH', `/api/tickets/${projector.id}/status`, {
    action: 'CLOSE',
    comment: '现场确认声音正常，感谢'
  })

  // 6) 管理员指派：新同事 CRM 登录失败
  const crm = await createTicket(emp2, 'demo-crm-login', {
    title: '新同事账号无法登录 CRM',
    description: '新同事今天入职，域账号可以登录邮箱，但 CRM 提示账号未开通。',
    category: 'PERMISSION',
    priority: 'MEDIUM'
  })
  await call(admin, 'POST', `/api/tickets/${crm.id}/assign`, { assigneeId: userId.demo_sup2 })

  // 7) 待接单：家庭 Wi-Fi
  await createTicket(emp2, 'demo-wifi-drop', {
    title: '家里 Wi-Fi 频繁掉线',
    description: '居家办公时 Wi-Fi 每隔十几分钟断一次，手机热点正常，路由器指示灯没有异常。',
    category: 'IT_FAULT',
    priority: 'LOW'
  })

  // 8) 重新打开：显示器闪烁，形成一条有波折的时间线
  const monitor = await createTicket(emp, 'demo-monitor-flicker', {
    title: '显示器闪烁，疑似线材问题',
    description: '外接显示器间歇性黑屏 1-2 秒，更换 HDMI 线后仍有发生。',
    category: 'DEVICE',
    priority: 'MEDIUM'
  })
  await call(sup2, 'POST', `/api/tickets/${monitor.id}/claim`, {})
  await call(sup2, 'PATCH', `/api/tickets/${monitor.id}/status`, {
    action: 'RESOLVE',
    comment: '更换扩展坞供电线后观察 30 分钟正常'
  })
  await call(emp, 'PATCH', `/api/tickets/${monitor.id}/status`, {
    action: 'REOPEN',
    comment: '下午又闪了一次，麻烦再排查一下'
  })

  // 9) 待接单：设计软件安装
  await createTicket(emp, 'demo-design-software', {
    title: '申请安装设计软件',
    description: '需要安装 Figma 桌面端和字体插件，用于市场部物料设计。',
    category: 'PERMISSION',
    priority: 'LOW'
  })

  // 知识草稿 A：保留 DRAFT，供「知识审核」截图
  const draft = await call(sup2, 'POST', '/api/kb/drafts/from-ticket', {
    ticketId: printer.id,
    environment: 'Windows 11 · HP LaserJet M404dn'
  })
  const draftEdited = await call(sup2, 'PATCH', `/api/kb/drafts/${draft.id}`, {
    title: '打印机卡纸后仍提示卡纸的排查步骤',
    content: [
      '## 适用环境',
      'Windows 11 · HP LaserJet M404dn（3 楼公共打印区）',
      '',
      '## 故障现象',
      '取出卡纸后面板仍提示卡纸，重启打印机无效，同一楼层其他同事也无法打印。',
      '',
      '## 处理步骤',
      '1. 断电并打开后盖，检查定影单元与出纸口是否有残留纸屑。',
      '2. 用干燥无绒布清洁卡纸传感器，避免纸屑遮挡。',
      '3. 复位所有舱门，重新上电，等待自检完成。',
      '4. 连续打印 20 页测试，确认不再触发卡纸提示。',
      '',
      '## 验证方法',
      '连续打印 20 页无中断，面板不再提示卡纸；同楼层其他同事可正常提交打印任务。',
      '',
      '## 预防建议',
      '纸张受潮时先烘干再装入纸盒；每月由行政检查一次进纸轮磨损情况。'
    ].join('\n'),
    tags: '打印机,卡纸,传感器,Windows 11',
    categoryId: categoryId['设备与外设'],
    environment: 'Windows 11 · HP LaserJet M404dn',
    reviewDueAt: futureDate(3),
    version: draft.version
  })

  // 知识草稿 B：走完整审核并发布，让知识库列表有真实条目
  const publishedDraft = await call(sup, 'POST', '/api/kb/drafts/from-ticket', {
    ticketId: projector.id,
    environment: '会议室 A · Windows 11 · HDMI'
  })
  const publishedEdited = await call(sup, 'PATCH', `/api/kb/drafts/${publishedDraft.id}`, {
    title: '会议室投屏画面正常但没有声音',
    content: [
      '## 适用环境',
      '会议室 A · Windows 11 · HDMI 投屏',
      '',
      '## 处理步骤',
      '1. 右键任务栏音量图标，打开「声音设置」。',
      '2. 将输出设备切换为显示器对应的 HDMI 音频设备。',
      '3. 仍无声时重启 Windows Audio 服务，并检查显示器静音开关。',
      '',
      '## 验证方法',
      '播放测试音频，确认会议室音箱出声且切换窗口后不回落。'
    ].join('\n'),
    tags: '会议室,投屏,HDMI,音频',
    categoryId: categoryId['设备与外设'],
    environment: '会议室 A · Windows 11 · HDMI',
    reviewDueAt: futureDate(6),
    version: publishedDraft.version
  })
  await call(sup, 'POST', `/api/kb/drafts/${publishedEdited.id}/publish`, {
    categoryId: categoryId['设备与外设'],
    version: publishedEdited.version
  })

  // AI 配置：真实保存但保持「待测试」，密钥是明显的占位值
  const deepseek = await call(admin, 'POST', '/api/admin/ai/configurations', {
    name: 'DeepSeek 服务台主通道',
    provider: 'DEEPSEEK',
    baseUrl: 'https://api.deepseek.com',
    model: 'deepseek-chat',
    apiKey: 'sk-demo-0000-1111-2222-9f3a',
    currency: 'CNY',
    inputPrice: 2,
    cachedInputPrice: 0.5,
    outputPrice: 8
  })
  const qwen = await call(admin, 'POST', '/api/admin/ai/configurations', {
    name: '通义千问 备用通道',
    provider: 'QWEN',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    model: 'qwen-plus',
    apiKey: 'sk-demo-3333-4444-5555-7b2c',
    currency: 'USD',
    inputPrice: 0.4,
    cachedInputPrice: 0.1,
    outputPrice: 1.2
  })

  // AI 用量台账：明确标注为模拟数据，覆盖多用户、多供应商、多币种与「用量不完整」
  const configs = [deepseek, qwen]
  const purposes = ['CHAT', 'CHAT', 'CHAT', 'DRAFT', 'DRAFT', 'ASSIST', 'KNOWLEDGE_DRAFT']
  const userIds = [userId.demo_emp, userId.demo_emp2, userId.demo_sup, userId.demo_sup2, userId.demo_admin]
  let seed = 20261103
  const random = () => {
    seed = (seed * 1103515245 + 12345) % 2147483648
    return seed / 2147483648
  }
  const rows = []
  for (let i = 0; i < 32; i += 1) {
    const config = configs[i % 2]
    const complete = i < 29
    const inputTokens = Math.round(1200 + random() * 9000)
    const cachedTokens = Math.round(inputTokens * 0.2)
    const outputTokens = Math.round(400 + random() * 2600)
    const cost = complete
      ? ((inputTokens - cachedTokens) * Number(config.inputPrice) +
          cachedTokens * Number(config.cachedInputPrice) +
          outputTokens * Number(config.outputPrice)) /
        1_000_000
      : null
    const daysAgo = i % 14
    const created = new Date(Date.now() - daysAgo * 86_400_000)
    created.setHours(9 + (i % 8), (i * 7) % 60, 0, 0)
    const pad = (value) => String(value).padStart(2, '0')
    const stamp = `${created.getFullYear()}-${pad(created.getMonth() + 1)}-${pad(created.getDate())} ` +
      `${pad(created.getHours())}:${pad(created.getMinutes())}:00`
    const upstream = complete ? 'SUCCESS' : i % 2 === 0 ? 'TIMEOUT' : 'ERROR'
    const business = complete ? 'SUCCESS' : 'FALLBACK'
    rows.push({
      id: randomUUID(),
      userId: userIds[i % userIds.length],
      purpose: purposes[i % purposes.length],
      config,
      model: config.model,
      currency: config.currency,
      inputTokens,
      cachedTokens,
      outputTokens,
      upstream,
      business,
      complete,
      cost,
      stamp,
      duration: Math.round(600 + random() * 2600),
      requests: complete ? 1 : 2
    })
  }
  const usageSql = rows
    .map(
      (row) => `('${row.id}', NULL, ${row.userId}, '${row.purpose}', ${row.config.id}, ${row.config.version},
        '${row.config.provider}', '${row.config.name.replace(/'/g, "''")}', '${row.config.keySuffix}',
        '${row.model}', '${row.model}', '${row.currency}', ${row.config.inputPrice}, ${row.config.cachedInputPrice},
        ${row.config.outputPrice}, '${row.business}', '${row.upstream}', ${row.requests},
        ${row.inputTokens}, ${row.cachedTokens}, ${row.outputTokens}, ${row.complete ? 1 : 0},
        ${row.cost === null ? 'NULL' : row.cost.toFixed(12)}, '${row.stamp}', NULL, ${row.duration})`
    )
    .join(',\n')
  sql(`
    SET NAMES utf8mb4;
    DELETE FROM ai_usage_call WHERE purpose <> 'CONNECTION_TEST';
    INSERT INTO ai_usage_call
      (id, trace_id, user_id, purpose, configuration_id, configuration_version, provider, configuration_name,
       key_suffix, model, actual_model, currency, input_price, cached_input_price, output_price,
       business_outcome, upstream_outcome, upstream_requests, input_tokens, cached_input_tokens,
       output_tokens, usage_complete, estimated_cost, created_at, completed_at, duration_ms)
    VALUES ${usageSql};
  `)

  const state = {
    baseUrl: BASE_URL,
    users: userId,
    tickets: {
      vpn: vpn.id,
      printer: printer.id,
      outlook: outlook.id,
      projector: projector.id,
      crm: crm.id,
      monitor: monitor.id
    },
    drafts: { pending: draftEdited.id, published: publishedEdited.id },
    configs: { deepseek: deepseek.id, qwen: qwen.id }
  }
  fs.writeFileSync(stateFile, `${JSON.stringify(state, null, 2)}\n`, 'utf8')
  console.log(`演示数据完成：${stateFile}`)
  for (const session of [emp, emp2, sup, sup2, admin]) {
    await session.context.dispose()
  }
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})

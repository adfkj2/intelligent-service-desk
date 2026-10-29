import http from 'k6/http'
import { check, fail } from 'k6'

/**
 * 压测公共部分：只有两件事——拼地址、登录。
 *
 * 会话是 Cookie（JSESSIONID），k6 的每个 VU 自带 cookie jar，
 * 所以同一 VU 里登录一次，后续请求自动带上会话，不需要手工传 token。
 */
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081'

export const ACCOUNT = {
  username: __ENV.ISD_USERNAME || 'perf_emp',
  password: __ENV.ISD_PASSWORD || 'perf12345'
}

export const JSON_HEADERS = { 'Content-Type': 'application/json' }

/**
 * k6 的 cookie jar 在每次迭代开始时会被重置，所以"登录一次、后面自动带 Cookie"
 * 这条路走不通（第二次迭代就 401）。做法是在 setup 里登录一次拿到 session id，
 * 每个迭代开始时用 cookieJar().set 塞回去——不再多发一次登录请求。
 */
export function loginOnce() {
  const res = login()
  const cookies = res.cookies.SESSION
  if (!cookies || cookies.length === 0) {
    fail('登录成功但响应里没有 SESSION Cookie，检查后端会话配置')
  }
  return cookies[0].value
}

export function useSession(sessionId) {
  http.cookieJar().set(BASE_URL, 'SESSION', sessionId)
}

export function login(username = ACCOUNT.username, password = ACCOUNT.password) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ username, password }), {
    headers: JSON_HEADERS,
    tags: { name: 'login' }
  })
  if (!check(res, { '登录成功': (r) => r.status === 200 })) {
    fail(`登录失败（${res.status}）：${res.body}`)
  }
  return res
}

/**
 * 造一条工单。
 * Idempotency-Key 用固定前缀 + 序号：压测数据重跑时不会被唯一索引拦成 409，
 * 也不会因为重复造数据把库越撑越大（同一个 key 只会成功一次）。
 */
export function createTicket(index, prefix = 'perf-seed') {
  return http.post(
    `${BASE_URL}/api/tickets`,
    JSON.stringify({
      title: `${prefix} 工单 ${index}`,
      description: '压测用数据，可随时通过 docker compose down -v 清理',
      category: 'IT_FAULT',
      priority: 'MEDIUM'
    }),
    {
      headers: { ...JSON_HEADERS, 'Idempotency-Key': `${prefix}-${index}` },
      tags: { name: 'seed' }
    }
  )
}

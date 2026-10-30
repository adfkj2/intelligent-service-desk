import http from 'k6/http'
import { check, fail } from 'k6'

/**
 * 压测公共部分：拼地址、拿 CSRF、登录。
 *
 * 会话是 Cookie（SESSION），k6 的每个 VU 自带 cookie jar；
 * 但写请求还要求 CSRF 双提交（Cookie XSRF-TOKEN + 请求头 X-XSRF-TOKEN），
 * 所以登录前先 GET 一次探活接口拿到 token，再把它塞进后续请求头。
 */
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081'

export const ACCOUNT = {
  username: __ENV.ISD_USERNAME || 'perf_emp',
  password: __ENV.ISD_PASSWORD || 'perf12345'
}

export const JSON_HEADERS = { 'Content-Type': 'application/json' }

/** 首个 GET 就会下发 XSRF-TOKEN（CsrfCookieFilter），会话建立前也能拿到 */
function bootstrapCsrf() {
  const res = http.get(`${BASE_URL}/api/ping`, { tags: { name: 'csrf_bootstrap' } })
  const cookie = res.cookies['XSRF-TOKEN']
  if (!cookie || cookie.length === 0) {
    fail('GET /api/ping 没有下发 XSRF-TOKEN Cookie，检查 CsrfCookieFilter 是否装配')
  }
  return cookie[0].value
}

/**
 * k6 的 cookie jar 在每次迭代开始时会被重置，所以"登录一次、后面自动带 Cookie"
 * 这条路走不通（第二次迭代就 401）。做法是在 setup 里登录一次拿到 session id，
 * 每个迭代开始时用 cookieJar().set 塞回去——不再多发一次登录请求。
 *
 * 返回 { session, csrf }：csrf 同时进 Cookie 与请求头，缺一个都会 403。
 */
export function loginOnce() {
  const csrf = bootstrapCsrf()
  const res = login(ACCOUNT.username, ACCOUNT.password, csrf)
  const cookies = res.cookies.SESSION
  if (!cookies || cookies.length === 0) {
    fail('登录成功但响应里没有 SESSION Cookie，检查后端会话配置')
  }
  return { session: cookies[0].value, csrf }
}

export function useSession(auth) {
  http.cookieJar().set(BASE_URL, 'SESSION', auth.session)
  http.cookieJar().set(BASE_URL, 'XSRF-TOKEN', auth.csrf)
}

/** 写请求通用头：CSRF token 必须与 Cookie 一致，否则被 403 拦下 */
export function authHeaders(auth, extra = {}) {
  return { ...JSON_HEADERS, 'X-XSRF-TOKEN': auth.csrf, ...extra }
}

export function login(username = ACCOUNT.username, password = ACCOUNT.password, csrf = bootstrapCsrf()) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ username, password }), {
    headers: authHeaders({ csrf }),
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
export function createTicket(index, prefix = 'perf-seed', auth) {
  return http.post(
    `${BASE_URL}/api/tickets`,
    JSON.stringify({
      title: `${prefix} 工单 ${index}`,
      description: '压测用数据，可随时通过 docker compose down -v 清理',
      category: 'IT_FAULT',
      priority: 'MEDIUM'
    }),
    {
      headers: authHeaders(auth, { 'Idempotency-Key': `${prefix}-${index}` }),
      tags: { name: 'seed' }
    }
  )
}

import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import { BASE_URL, authHeaders, loginOnce, useSession } from './common.js'

/**
 * AI 路径压测：验证的不是吞吐，而是两条护栏是否真的生效。
 *
 * 1. 没配真实 Key 时必须走知识库降级（source=FALLBACK），不能 5xx；
 * 2. 单用户配额 20 次/分钟，第 21 次起必须是 429，而不是继续烧 token。
 *
 * 所以这里用 1 个 VU 连续打 25 次：前 20 次 200，之后 429，
 * 并把 5xx 的阈值设成 0——「预期内的 429」与「真的挂了」必须能区分开。
 */
const quotaOk = new Counter('ai_quota_ok')
const rateLimited = new Counter('ai_rate_limited')
const serverError = new Counter('ai_server_error')

export const options = {
  scenarios: {
    quota: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: Number(__ENV.ITERATIONS || 25),
      maxDuration: '180s'
    }
  },
  thresholds: {
    // 配额被撞满是脚本设计的一部分；真正不能接受的是 5xx 与网关级失败
    ai_server_error: ['count==0'],
    ai_rate_limited: ['count>=1'],
    checks: ['rate>0.95']
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
}

export function setup() {
  // 会话只登录一次：cookie jar 每轮迭代会被重置，所以由 useSession 在迭代开始时塞回去
  return { auth: loginOnce() }
}

export default function (data) {
  useSession(data.auth)

  const res = http.post(
    `${BASE_URL}/api/ai/chat`,
    JSON.stringify({ question: '如何重置密码？' }),
    { headers: authHeaders(data.auth), tags: { name: 'ai_chat' } }
  )

  if (res.status === 200) {
    quotaOk.add(1)
    check(res, {
      '降级答案来自知识库': (r) => r.json('data.source') === 'FALLBACK'
    })
  } else if (res.status === 429) {
    rateLimited.add(1)
    check(res, {
      '429 带业务码与文案': (r) => r.json('code') === 42900 && Boolean(r.json('message'))
    })
  } else {
    serverError.add(1)
    console.error(`AI 接口异常：${res.status} ${res.body}`)
  }
}

import http from 'k6/http'
import { check, fail, sleep } from 'k6'
import { BASE_URL, createTicket, loginOnce, useSession } from './common.js'

/**
 * 工单读路径压测：列表（无关键词 / 有关键词）+ 详情。
 *
 * 为什么把"有关键词"单独打标签：它是唯一走 LIKE '%kw%' 的路径，
 * 会把性能差异直接暴露在 p95 上，这正是要被量化而不是被掩盖的瓶颈。
 */
export const options = {
  scenarios: {
    browse: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 10),
      duration: __ENV.DURATION || '20s'
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:list}': ['p(95)<300'],
    'http_req_duration{name:list_kw}': ['p(95)<500'],
    'http_req_duration{name:detail}': ['p(95)<200']
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
}

export function setup() {
  const session = loginOnce()
  const ids = []
  const total = Number(__ENV.SEED_TICKETS || 50)
  for (let i = 1; i <= total; i += 1) {
    const res = createTicket(i)
    if (res.status === 201 || res.status === 200) {
      // 201 = 新建；200 = 幂等键重复，后端直接把上一次的结果返回（重跑时走这条）
      const id = res.json('data.id')
      if (id) {
        ids.push(id)
      }
    } else if (res.status !== 409) {
      fail(`造数据失败（${res.status}）：${res.body}`)
    }
  }
  check(ids, { '种子工单可用': (list) => list.length > 0 })
  return { ids, session }
}

export default function (data) {
  // 每轮迭代开始时 cookie jar 是空的，先把 setup 里拿到的 session 塞回去
  useSession(data.session)

  const page = 1 + Math.floor(Math.random() * 3)
  http.get(`${BASE_URL}/api/tickets?page=${page}&size=10`, { tags: { name: 'list' } })

  const keyword = encodeURIComponent('压测')
  http.get(`${BASE_URL}/api/tickets?page=1&size=10&keyword=${keyword}`, { tags: { name: 'list_kw' } })

  const id = data.ids[Math.floor(Math.random() * data.ids.length)]
  http.get(`${BASE_URL}/api/tickets/${id}`, { tags: { name: 'detail' } })

  sleep(0.3)
}

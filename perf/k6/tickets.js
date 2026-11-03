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
  const auth = loginOnce()
  const ids = []
  const total = Number(__ENV.SEED_TICKETS || 50)
  for (let i = 1; i <= total; i += 1) {
    const res = createTicket(i, 'perf-seed', auth)
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

  // 冷启动预热：JVM JIT、Hikari 连接池、MyBatis 语句缓存、Redis 会话都在第一次访问时才真正就绪。
  // 预热放在 setup 里，被测场景一开始就是热态；否则前一两百毫秒的编译与建连开销会直接落进 p95，
  // 测出来的是启动耗时而不是接口性能。预热请求统一打 name:warmup，不进被测阈值。
  for (let i = 0; ids.length > 0 && i < 20; i += 1) {
    http.get(`${BASE_URL}/api/tickets?page=1&size=10`, { tags: { name: 'warmup' } })
    http.get(`${BASE_URL}/api/tickets/${ids[i % ids.length]}`, { tags: { name: 'warmup' } })
  }

  return { ids, auth }
}

export default function (data) {
  // 每轮迭代开始时 cookie jar 是空的，先把 setup 里拿到的会话与 CSRF 塞回去
  useSession(data.auth)

  const page = 1 + Math.floor(Math.random() * 3)
  http.get(`${BASE_URL}/api/tickets?page=${page}&size=10`, { tags: { name: 'list' } })

  const keyword = encodeURIComponent('压测')
  http.get(`${BASE_URL}/api/tickets?page=1&size=10&keyword=${keyword}`, { tags: { name: 'list_kw' } })

  const id = data.ids[Math.floor(Math.random() * data.ids.length)]
  http.get(`${BASE_URL}/api/tickets/${id}`, { tags: { name: 'detail' } })

  sleep(0.3)
}

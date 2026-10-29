import axios from 'axios'
import { ElMessage } from 'element-plus'
import { safeRedirect } from '../utils/redirect'

/**
 * 统一请求层。
 * 后端约定：HTTP 状态码保留语义，body 恒为 {code, message, data}。
 * 这里做两件事：解包 data；把 401/403/404/409 翻译成人话。
 */
const service = axios.create({
  baseURL: '/api',
  timeout: 60000,
  // Session 方案：Cookie 即凭证，前后端同源（开发期由 Vite 代理保证）
  withCredentials: true
})

service.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      if (!response.config?.silent) {
        ElMessage.error(body.message || '请求失败')
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body ? body.data : null
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    /*
     * message 不在这里兜底成"网络异常"：兜底会盖掉下面每个状态码自己的默认文案
     * （403 的"没有权限"、409 的"数据已被他人修改"），只剩一句没有指引的通用错误。
     */
    const message = body?.message
    const url = error.config?.url || ''
    /*
     * silent：调用方明确表示"失败不用提示用户"，用于后台轮询（未读数）这类请求。
     * 没有它的话，后端重启 30 秒就会弹一次"网络异常"，比收不到通知更打扰人。
     * 但 401 仍然照常跳登录——会话真的失效时必须离开当前页。
     */
    const silent = error.config?.silent === true

    if (status === 401) {
      if (url.includes('/auth/login')) {
        // 登录接口的 401 是"用户名或密码错误"，直接展示后端文案，不能提示"登录已失效"
        if (!silent) {
          ElMessage.error(message || '用户名或密码错误')
        }
      } else if (!url.includes('/auth/me')) {
        // /auth/me 的 401 是"未登录"的正常探测，交给路由守卫处理，不在这里提示与跳转
        if (!silent) {
          ElMessage.warning('登录已失效，请重新登录')
        }
        // 整页跳转会重新初始化 Pinia，等价于清空会话 store，避免残留上一个用户的菜单与数据。
        // 当前停在改密页/登录页时不带 redirect：那两条路径不能作为登录后的落点。
        const redirect = safeRedirect(window.location.pathname + window.location.search, '')
        window.location.assign(redirect ? `/login?redirect=${encodeURIComponent(redirect)}` : '/login')
      }
    } else if (status === 403) {
      if (!silent) {
        ElMessage.error(message || '没有权限执行该操作')
      }
    } else if (status === 409) {
      // 并发接单等业务冲突属于预期结果，用 warning 而不是 error 弹窗
      if (!silent) {
        ElMessage.warning(message || '数据已被他人修改，请刷新后重试')
      }
    } else if (status === 404) {
      if (!silent) {
        ElMessage.error(message || '资源不存在')
      }
    } else if (status === 429) {
      // 登录失败次数过多被锁：后端文案里带剩余分钟数，直接用，别覆盖成"未知错误"
      if (!silent) {
        ElMessage.warning(message || '操作过于频繁，请稍后再试')
      }
    } else if (!silent) {
      ElMessage.error(message || '网络异常，请稍后重试')
    }
    return Promise.reject(error)
  }
)

export default service

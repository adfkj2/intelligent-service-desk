import axios from 'axios'
import { ElMessage } from 'element-plus'

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
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body ? body.data : null
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    const message = body?.message || '网络异常，请稍后重试'
    const url = error.config?.url || ''

    if (status === 401) {
      if (url.includes('/auth/login')) {
        // 登录接口的 401 是"用户名或密码错误"，直接展示后端文案，不能提示"登录已失效"
        ElMessage.error(message)
      } else if (!url.includes('/auth/me')) {
        // /auth/me 的 401 是"未登录"的正常探测，交给路由守卫处理，不在这里提示与跳转
        ElMessage.warning('登录已失效，请重新登录')
        // 整页跳转会重新初始化 Pinia，等价于清空会话 store，避免残留上一个用户的菜单与数据
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.assign(`/login?redirect=${redirect}`)
      }
    } else if (status === 403) {
      ElMessage.error(message || '没有权限执行该操作')
    } else if (status === 409) {
      // 并发接单等业务冲突属于预期结果，用 warning 而不是 error 弹窗
      ElMessage.warning(message || '数据已被他人修改，请刷新后重试')
    } else if (status === 404) {
      ElMessage.error(message || '资源不存在')
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

export default service

import request from './request'

/**
 * 站内信接口。
 * 后端只认当前登录用户（条件从 Session 取，前端传不了 userId），所以这里没有"查谁"的参数。
 *
 * 轮询/顺手标记这类非用户主动触发的请求带 silent：
 * 失败了不应该弹 toast（每 30 秒弹一次"网络异常"比没有通知更烦），交给下一轮轮询自愈。
 */
export const notificationApi = {
  list: (limit = 20) => request.get('/notifications', { params: { limit } }),
  unreadCount: () => request.get('/notifications/unread-count', { silent: true }),
  markRead: (id) => request.patch(`/notifications/${id}/read`, null, { silent: true }),
  markAllRead: () => request.post('/notifications/read-all', null, { silent: true })
}

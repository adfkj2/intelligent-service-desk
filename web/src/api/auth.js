import request from './request'

export const authApi = {
  register: (data) => request.post('/auth/register', data),
  login: (data) => request.post('/auth/login', data),
  logout: () => request.post('/auth/logout'),
  me: () => request.get('/auth/me'),
  // 公开接口：登录页“忘记密码”每次打开实时拉取，不做缓存
  adminContacts: () => request.get('/auth/admin-contacts'),
  updateContact: (data) => request.put('/auth/me/contact', data),
  changePassword: (data) => request.post('/auth/change-password', data)
}

import request from './request'

export const adminApi = {
  users: () => request.get('/admin/users'),
  updateStatus: (id, status) => request.patch(`/admin/users/${id}/status`, { status }),
  // role: EMPLOYEE / SUPPORT / ADMIN
  updateRole: (id, role) => request.patch(`/admin/users/${id}/role`, { role }),
  // 返回 { userId, username, temporaryPassword, mustChangePassword }，明文只出现这一次
  resetPassword: (id) => request.post(`/admin/users/${id}/password/reset`)
}

import request from './request'

export const ticketApi = {
  // params: { page, size, status, category, keyword }
  list: (params) => request.get('/tickets', { params }),
  detail: (id) => request.get(`/tickets/${id}`),
  // Idempotency-Key：同一个 key 重复提交只会落一条工单，后端命中时返回 200 + Idempotent-Replay
  create: (data, idempotencyKey) =>
    request.post('/tickets', data, idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : undefined),
  claim: (id) => request.post(`/tickets/${id}/claim`),
  assign: (id, assigneeId) => request.post(`/tickets/${id}/assign`, { assigneeId }),
  changeStatus: (id, data) => request.patch(`/tickets/${id}/status`, data),
  operations: (id) => request.get(`/tickets/${id}/operations`),
  replies: (id) => request.get(`/tickets/${id}/replies`),
  reply: (id, data) => request.post(`/tickets/${id}/replies`, data),
  attachments: (id) => request.get(`/tickets/${id}/attachments`),
  upload: (id, file, visibility = 'PUBLIC') => {
    const data = new FormData()
    data.append('file', file)
    data.append('visibility', visibility)
    return request.post(`/tickets/${id}/attachments`, data)
  },
  attachmentUrl: (id, attachmentId) => `/api/tickets/${Number(id)}/attachments/${Number(attachmentId)}`,
  transfer: (id, data) => request.post(`/tickets/${id}/transfer`, data),
  overview: () => request.get('/tickets/overview'),
  supportOptions: () => request.get('/tickets/support-options'),
  assignmentSuggestions: (id) => request.get(`/tickets/${id}/assignment-suggestions`),
  saveSkills: (skills) => request.put('/tickets/support-profile', { skills })
}

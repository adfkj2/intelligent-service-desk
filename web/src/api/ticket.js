import request from './request'

export const ticketApi = {
  // params: { page, size, status, category, keyword }
  list: (params) => request.get('/tickets', { params }),
  detail: (id) => request.get(`/tickets/${id}`),
  create: (data) => request.post('/tickets', data),
  claim: (id) => request.post(`/tickets/${id}/claim`),
  assign: (id, assigneeId) => request.post(`/tickets/${id}/assign`, { assigneeId }),
  changeStatus: (id, data) => request.patch(`/tickets/${id}/status`, data),
  operations: (id) => request.get(`/tickets/${id}/operations`)
}

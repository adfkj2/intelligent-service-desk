import request from './request'

export const aiApi = {
  chat: (data) => request.post('/ai/chat', typeof data === 'string' ? { question: data } : data),
  draft: (data) => request.post('/ai/draft', data),
  assist: (id) => request.post(`/ai/tickets/${id}/assist`),
  conversation: (id) => request.get(`/ai/conversations/${encodeURIComponent(id)}`),
  feedback: (id, data) => request.post(`/ai/answers/${id}/feedback`, data),
  metrics: () => request.get('/ai/metrics')
}

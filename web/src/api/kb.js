import request from './request'

export const kbApi = {
  categories: () => request.get('/kb/categories'),
  createCategory: (data) => request.post('/kb/categories', data),
  // params: { categoryId, page, size }
  items: (params) => request.get('/kb/items', { params }),
  item: (id) => request.get(`/kb/items/${id}`),
  createItem: (data) => request.post('/kb/items', data),
  updateItem: (id, data) => request.put(`/kb/items/${id}`, data),
  deleteItem: (id) => request.delete(`/kb/items/${id}`),
  // params: { q, page, size }
  search: (params) => request.get('/kb/search', { params }),
  drafts: () => request.get('/kb/drafts'),
  draftFromTicket: (data) => request.post('/kb/drafts/from-ticket', data),
  updateDraft: (id, data) => request.patch(`/kb/drafts/${id}`, data),
  publishDraft: (id, data) => request.post(`/kb/drafts/${id}/publish`, data),
  feedbackSummary: (id) => request.get(`/kb/items/${id}/feedback`),
  feedback: (id, helpful) => request.post(`/kb/items/${id}/feedback`, { helpful }),
  review: (id, data) => request.post(`/kb/items/${id}/review`, data)
}

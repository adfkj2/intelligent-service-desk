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
  search: (params) => request.get('/kb/search', { params })
}

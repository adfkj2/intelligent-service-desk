import request from './request'

const root = '/admin/ai'

export const adminAiApi = {
  configurations: () => request.get(`${root}/configurations`),
  versions: (id) => request.get(`${root}/configurations/${id}/versions`),
  create: (payload) => request.post(`${root}/configurations`, payload),
  update: (id, payload) => request.put(`${root}/configurations/${id}`, payload),
  test: (id, version) => request.post(`${root}/configurations/${id}/test`, { version }),
  archive: (id, version) => request.post(`${root}/configurations/${id}/archive`, { version }),
  runtime: () => request.get(`${root}/runtime`),
  setRuntime: (payload) => request.put(`${root}/runtime`, payload),
  balance: (id) => request.get(`${root}/configurations/${id}/balance`),
  refreshBalance: (id) => request.post(`${root}/configurations/${id}/balance/refresh`),
  summary: (params) => request.get(`${root}/usage/summary`, { params }),
  daily: (params) => request.get(`${root}/usage/daily`, { params }),
  users: (params) => request.get(`${root}/usage/users`, { params }),
  calls: (params) => request.get(`${root}/usage/calls`, { params })
}

import request from './request'

export const aiApi = {
  chat: (question) => request.post('/ai/chat', { question })
}

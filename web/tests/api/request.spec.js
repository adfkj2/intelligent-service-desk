import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import service from '../../src/api/request'

/**
 * 请求层的约定：
 * - 成功响应只把 data 交给调用方（页面里不用重复 body.data）；
 * - 业务 code != 0 视为失败，抛错并提示；
 * - HTTP 错误按状态码翻译，401 整页跳登录；
 * - 带 silent 的请求（后台轮询）失败不弹提示，但 401 仍要跳登录。
 *
 * 用 axios 的 adapter 注入替身，不 mock axios 本身：这样拦截器、错误包装都是真实路径。
 */
function respondWith(body, status = 200) {
  service.defaults.adapter = async (config) => ({
    data: body,
    status,
    statusText: 'OK',
    headers: {},
    config
  })
}

function failWith(body, status) {
  service.defaults.adapter = async (config) =>
    Promise.reject({ response: { status, data: body }, config, message: 'stub' })
}

const originalAdapter = service.defaults.adapter
const assignSpy = vi.fn()

beforeEach(() => {
  // jsdom 的 location.assign 会真的尝试导航并抛 "Not implemented"，替换成 spy
  delete window.location
  window.location = { pathname: '/tickets', search: '?page=2', assign: assignSpy }
})

afterEach(() => {
  service.defaults.adapter = originalAdapter
  assignSpy.mockReset()
})

describe('request 响应拦截器', () => {
  it('成功时解包 data', async () => {
    respondWith({ code: 0, message: 'OK', data: { id: 7 } })
    await expect(service.get('/tickets/7')).resolves.toEqual({ id: 7 })
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('业务 code != 0 时抛错并提示后端文案', async () => {
    respondWith({ code: 40001, message: '参数不合法', data: null })
    await expect(service.get('/x')).rejects.toThrow('参数不合法')
    expect(ElMessage.error).toHaveBeenCalledWith('参数不合法')
  })

  it('silent 请求的业务错误不弹提示', async () => {
    respondWith({ code: 50000, message: '服务繁忙', data: null })
    await expect(service.get('/notifications', { silent: true })).rejects.toThrow('服务繁忙')
    expect(ElMessage.error).not.toHaveBeenCalled()
  })

  it('401 非登录接口：提示失效并整页跳登录，redirect 带上当前地址', async () => {
    failWith({ message: '未登录' }, 401)
    await expect(service.get('/tickets')).rejects.toBeTruthy()
    expect(ElMessage.warning).toHaveBeenCalledWith('登录已失效，请重新登录')
    expect(assignSpy).toHaveBeenCalledWith('/login?redirect=%2Ftickets%3Fpage%3D2')
  })

  it('401 登录接口：只展示后端文案，不跳转', async () => {
    failWith({ message: '用户名或密码错误' }, 401)
    await expect(service.post('/auth/login', {})).rejects.toBeTruthy()
    expect(ElMessage.error).toHaveBeenCalledWith('用户名或密码错误')
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('401 /auth/me：探测未登录，既不提示也不跳转', async () => {
    failWith({ message: '未登录' }, 401)
    await expect(service.get('/auth/me')).rejects.toBeTruthy()
    expect(ElMessage.warning).not.toHaveBeenCalled()
    expect(ElMessage.error).not.toHaveBeenCalled()
    expect(assignSpy).not.toHaveBeenCalled()
  })

  it('403 / 409 / 429 使用各自的提示级别与兜底文案', async () => {
    failWith({}, 403)
    await expect(service.get('/admin/users')).rejects.toBeTruthy()
    expect(ElMessage.error).toHaveBeenCalledWith('没有权限执行该操作')

    failWith({}, 409)
    await expect(service.post('/tickets/1/claim')).rejects.toBeTruthy()
    expect(ElMessage.warning).toHaveBeenCalledWith('数据已被他人修改，请刷新后重试')

    failWith({ message: 'AI 调用过于频繁，请 42 秒后重试' }, 429)
    await expect(service.post('/ai/chat', {})).rejects.toBeTruthy()
    expect(ElMessage.warning).toHaveBeenCalledWith('AI 调用过于频繁，请 42 秒后重试')
  })

  it('silent 的 429 不弹提示（后台轮询不应打扰用户）', async () => {
    failWith({ message: '太频繁' }, 429)
    await expect(service.get('/notifications/unread-count', { silent: true })).rejects.toBeTruthy()
    expect(ElMessage.warning).not.toHaveBeenCalled()
  })
})

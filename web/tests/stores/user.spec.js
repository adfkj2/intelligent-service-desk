import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

vi.mock('../../src/api/auth', () => ({
  authApi: {
    me: vi.fn(),
    login: vi.fn(),
    logout: vi.fn()
  }
}))

import { authApi } from '../../src/api/auth'
import { useUserStore } from '../../src/stores/user'

/**
 * 会话 store 只存「当前用户是谁」，角色的派生值统一从这里出。
 * 断言派生结果而不是实现细节：以后把 user 拆成多个 ref 也不该改测试。
 */
describe('user store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('初始为未登录状态', () => {
    const store = useUserStore()
    expect(store.user).toBeNull()
    expect(store.role).toBe('')
    expect(store.displayName).toBe('')
    expect(store.isAdmin).toBe(false)
    expect(store.isStaff).toBe(false)
  })

  it('管理员登录后各级派生值正确', async () => {
    authApi.login.mockResolvedValue({
      id: 1,
      username: 'demo_admin',
      fullName: '管理员',
      role: 'ADMIN',
      mustChangePassword: true
    })
    const store = useUserStore()
    await store.login({ username: 'demo_admin', password: 'x' })

    expect(store.isAdmin).toBe(true)
    expect(store.isStaff).toBe(true)
    expect(store.displayName).toBe('管理员')
    expect(store.mustChangePassword).toBe(true)
  })

  it('技术支持算 staff 但不是 admin', async () => {
    authApi.login.mockResolvedValue({ id: 2, username: 'demo_sup', role: 'SUPPORT' })
    const store = useUserStore()
    await store.login({})

    expect(store.isSupport).toBe(true)
    expect(store.isStaff).toBe(true)
    expect(store.isAdmin).toBe(false)
    // 没有 fullName 时回落到 username，避免顶栏显示空白
    expect(store.displayName).toBe('demo_sup')
  })

  it('普通员工不是 staff', async () => {
    authApi.login.mockResolvedValue({ id: 3, username: 'demo_emp', role: 'EMPLOYEE' })
    const store = useUserStore()
    await store.login({})

    expect(store.isStaff).toBe(false)
    expect(store.role).toBe('EMPLOYEE')
  })

  it('fetchMe 失败时清空用户并返回 false', async () => {
    authApi.me.mockRejectedValue(new Error('401'))
    const store = useUserStore()
    store.setUser({ id: 9, role: 'ADMIN' })

    await expect(store.fetchMe()).resolves.toBe(false)
    expect(store.user).toBeNull()
  })

  it('fetchMe 成功时写入用户并返回 true', async () => {
    authApi.me.mockResolvedValue({ id: 4, username: 'demo_emp', role: 'EMPLOYEE' })
    const store = useUserStore()

    await expect(store.fetchMe()).resolves.toBe(true)
    expect(store.userId).toBe(4)
  })

  it('登出接口失败也要清空本地会话', async () => {
    authApi.logout.mockRejectedValue(new Error('网络异常'))
    const store = useUserStore()
    store.setUser({ id: 5, role: 'ADMIN' })

    await expect(store.logout()).rejects.toThrow('网络异常')
    expect(store.user).toBeNull()
  })
})

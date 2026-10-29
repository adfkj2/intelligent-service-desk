import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: pushMock })
}))

vi.mock('../../src/api/notification', () => ({
  notificationApi: {
    unreadCount: vi.fn(),
    list: vi.fn(),
    markRead: vi.fn(),
    markAllRead: vi.fn()
  }
}))

import { notificationApi } from '../../src/api/notification'
import NotificationBell from '../../src/components/NotificationBell.vue'

/**
 * el-popover / el-badge 在 main.js 里是全局注册的，单测不会执行 main.js，
 * 所以用轻量 stub 替代：只保留「插槽渲染 + value 属性」这两个断言需要的能力。
 */
const stubs = {
  'el-popover': {
    template: '<div class="popover"><slot name="reference" /><div class="popover-body"><slot /></div></div>'
  },
  'el-badge': {
    props: ['value', 'hidden'],
    template: '<span class="badge" :data-value="value"><slot /></span>'
  },
  'el-tag': { template: '<span class="tag"><slot /></span>' }
}

const directives = { loading: {} }

function mountBell() {
  return mount(NotificationBell, {
    global: { stubs, directives }
  })
}

let wrapper

beforeEach(() => {
  // 组件挂载会起 30s 轮询：测试里不真的挂定时器，避免用例结束后继续跑
  vi.spyOn(window, 'setInterval').mockReturnValue(1)
  vi.spyOn(window, 'clearInterval').mockImplementation(() => {})
  notificationApi.list.mockResolvedValue([])
  notificationApi.markRead.mockResolvedValue(null)
  notificationApi.markAllRead.mockResolvedValue(null)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.restoreAllMocks()
})

describe('NotificationBell', () => {
  it('挂载后拉取未读数并渲染徽标', async () => {
    notificationApi.unreadCount.mockResolvedValue({ count: 5 })
    wrapper = mountBell()
    await flushPromises()

    expect(notificationApi.unreadCount).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.badge').attributes('data-value')).toBe('5')
    expect(wrapper.text()).toContain('5 条未读')
  })

  it('轮询失败只记 debug，不抛错也不打扰用户', async () => {
    const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {})
    notificationApi.unreadCount.mockRejectedValue(new Error('网络异常'))
    wrapper = mountBell()
    await flushPromises()

    expect(debugSpy).toHaveBeenCalledWith('未读数刷新失败', expect.any(Error))
    expect(wrapper.find('.badge').attributes('data-value')).toBe('0')
    expect(wrapper.text()).toContain('暂无通知')
  })

  it('点「全部已读」会调用接口并把未读数清零', async () => {
    notificationApi.unreadCount.mockResolvedValue({ count: 3 })
    wrapper = mountBell()
    await flushPromises()

    const markAllButton = wrapper.findAll('button').find((btn) => btn.text().includes('全部已读'))
    expect(markAllButton).toBeTruthy()
    await markAllButton.trigger('click')
    await flushPromises()

    expect(notificationApi.markAllRead).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.badge').attributes('data-value')).toBe('0')
  })
})

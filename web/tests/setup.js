import { beforeEach, vi } from 'vitest'

/**
 * 全局测试准备。
 *
 * element-plus 的 ElMessage 会真的往 DOM 里插节点：在 jsdom 下既慢又难断言，
 * 这里整体替换成 spy，测试只关心「有没有提示、提示了什么」，不关心弹窗长什么样。
 * 组件测试里用 stubs 处理 el-* 组件（它们在 main.js 里是全局注册的，单测不会执行 main.js）。
 */
vi.mock('element-plus', () => {
  const ElMessage = {
    error: vi.fn(),
    warning: vi.fn(),
    success: vi.fn(),
    info: vi.fn()
  }
  return { ElMessage, default: {} }
})

// spy 的调用记录不能跨用例累积，否则「上一条用例弹过提示」会让断言假通过
beforeEach(() => {
  vi.clearAllMocks()
})

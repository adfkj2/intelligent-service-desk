import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const { confirm, messages } = vi.hoisted(() => ({ confirm: vi.fn(), messages: { warning: vi.fn(), success: vi.fn() } }))
vi.mock('element-plus', () => ({ ElMessage: messages, ElMessageBox: { confirm } }))
vi.mock('../../src/api/adminAi', () => ({ adminAiApi: Object.fromEntries(['configurations', 'versions', 'create', 'update', 'test', 'archive', 'runtime', 'setRuntime', 'balance', 'refreshBalance', 'summary', 'daily', 'users', 'calls'].map(key => [key, vi.fn()])) }))
vi.mock('../../src/api/admin', () => ({ adminApi: { users: vi.fn() } }))
import { adminAiApi } from '../../src/api/adminAi'
import { adminApi } from '../../src/api/admin'
import AdminAiView from '../../src/views/AdminAiView.vue'

const configuration = { id: 1, version: 2, name: '服务台 DeepSeek', provider: 'DEEPSEEK', baseUrl: 'https://api.deepseek.com', model: 'deepseek-chat', keySuffix: 'abcd', currency: 'CNY', inputPrice: '2', cachedInputPrice: '0.2', outputPrice: '3', testStatus: 'UNTESTED', current: false }
const runtime = { mode: 'MANAGED', version: 7, configurationId: 1, configurationVersion: 1, provider: 'DEEPSEEK', model: 'deepseek-chat', keyConfigured: true, credentialsReady: true, message: '正在使用已启用版本。' }
const stubs = {
  'el-button': { props: ['nativeType', 'disabled', 'loading'], template: '<button :type="nativeType || \'button\'" :disabled="disabled || loading"><slot /></button>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-select': { props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><option value=""></option><slot /></select>' },
  'el-option': { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  'el-dialog': { props: ['modelValue', 'title'], template: '<section v-if="modelValue" role="dialog" :aria-label="title"><h2>{{ title }}</h2><slot /><slot name="footer" /></section>' },
  'el-pagination': { props: ['currentPage', 'total'], emits: ['current-change'], template: '<button v-if="total > 10" @click="$emit(\'current-change\', currentPage + 1)">下一页</button>' }
}
function render() { return mount(AdminAiView, { global: { stubs, directives: { loading: () => {} } } }) }
function button(wrapper, text) { return wrapper.findAll('button').find(item => item.text() === text) }

beforeEach(() => {
  vi.resetAllMocks()
  confirm.mockResolvedValue('confirm')
  adminAiApi.configurations.mockResolvedValue([{ ...configuration }])
  adminAiApi.runtime.mockResolvedValue({ ...runtime })
  adminAiApi.versions.mockResolvedValue([{ ...configuration, version: 1, testStatus: 'PASSED' }, configuration])
  adminAiApi.create.mockResolvedValue({ ...configuration, id: 2, version: 1 })
  adminAiApi.update.mockResolvedValue({ ...configuration, version: 3 })
  adminAiApi.test.mockResolvedValue({ ...configuration, testStatus: 'PASSED' })
  adminAiApi.setRuntime.mockResolvedValue({ ...runtime, version: 8 })
  adminAiApi.summary.mockResolvedValue({ calls: 2, upstreamRequests: 3, failedCalls: 1, pendingCalls: 0, unknownUsageCalls: 1, inputTokens: 200, cachedInputTokens: 20, outputTokens: 30, costs: [{ currency: 'CNY', estimatedCost: '0.00045', unknownCostCalls: 1 }, { currency: 'USD', estimatedCost: '0.001', unknownCostCalls: 0 }] })
  adminAiApi.daily.mockResolvedValue([{ date: '2026-11-03', calls: 2, upstreamRequests: 3, inputTokens: 200, outputTokens: 30 }])
  adminAiApi.users.mockResolvedValue([{ userId: 1, fullName: '陈晓', username: 'chen', calls: 2, inputTokens: 200, outputTokens: 30 }])
  adminAiApi.calls.mockResolvedValue({ page: 1, size: 10, total: 11, items: [{ id: 'c1', userId: 1, fullName: '陈晓', purpose: 'CHAT', provider: 'DEEPSEEK', model: 'deepseek-chat', configurationName: configuration.name, configurationVersion: 1, keySuffix: 'abcd', businessOutcome: 'TIMEOUT', upstreamOutcome: 'ERROR', upstreamRequests: 2, inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, usageComplete: false, estimatedCost: null, createdAt: '2026-11-03T10:00:00' }] })
  adminApi.users.mockResolvedValue([{ id: 1, fullName: '陈晓', username: 'chen' }])
})

describe('管理员 AI 配置与用量', () => {
  it('保存新的待启用版本不切换服务，并在保存后清除输入的 Key', async () => {
    const wrapper = render()
    await flushPromises()
    expect(wrapper.text()).toContain('正在使用版本 1')
    await button(wrapper, '新增配置').trigger('click')
    await wrapper.get('[aria-label="配置名称"]').setValue('备用服务')
    await wrapper.get('[aria-label="模型 API Key"]').setValue('sk-entered-only-in-memory')
    await button(wrapper, '保存待启用版本').trigger('click')
    await flushPromises()
    expect(adminAiApi.create).toHaveBeenCalledWith(expect.objectContaining({ version: null, name: '备用服务', apiKey: 'sk-entered-only-in-memory', inputPrice: null, currency: null }))
    expect(adminAiApi.setRuntime).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('sk-entered-only-in-memory')
    await button(wrapper, '新增配置').trigger('click')
    expect(wrapper.get('[aria-label="模型 API Key"]').element.value).toBe('')
    wrapper.unmount()
  })

  it('修改时 Key 留空保留旧凭据，取消编辑会清除输入', async () => {
    const wrapper = render()
    await flushPromises()
    await button(wrapper, '编辑').trigger('click')
    expect(wrapper.get('[aria-label="模型 API Key"]').element.value).toBe('')
    await wrapper.get('[aria-label="配置名称"]').setValue('改名后的服务')
    await button(wrapper, '保存待启用版本').trigger('click')
    await flushPromises()
    expect(adminAiApi.update).toHaveBeenCalledWith(1, expect.objectContaining({ version: 2, name: '改名后的服务', apiKey: null, inputPrice: '2' }))
    await button(wrapper, '编辑').trigger('click')
    await wrapper.get('[aria-label="模型 API Key"]').setValue('sk-do-not-retain')
    await button(wrapper, '取消').trigger('click')
    await button(wrapper, '编辑').trigger('click')
    expect(wrapper.get('[aria-label="模型 API Key"]').element.value).toBe('')
    wrapper.unmount()
  })

  it('连接测试需要确认且不会启用，显式切换使用 runtime 与配置版本', async () => {
    const wrapper = render()
    await flushPromises()
    expect(button(wrapper, '设为当前').attributes('disabled')).toBeDefined()
    confirm.mockRejectedValueOnce('cancel')
    await button(wrapper, '测试连接').trigger('click')
    await flushPromises()
    expect(adminAiApi.test).not.toHaveBeenCalled()
    adminAiApi.configurations.mockResolvedValue([{ ...configuration, testStatus: 'PASSED' }])
    await button(wrapper, '测试连接').trigger('click')
    await flushPromises()
    expect(confirm.mock.calls.at(-1)[0]).toContain('少量 Token 费用')
    expect(adminAiApi.test).toHaveBeenCalledWith(1, 2)
    expect(adminAiApi.setRuntime).not.toHaveBeenCalled()
    await button(wrapper, '设为当前').trigger('click')
    await flushPromises()
    expect(adminAiApi.setRuntime).toHaveBeenCalledWith({ version: 7, mode: 'MANAGED', configurationId: 1, configurationVersion: 2 })
    wrapper.unmount()
  })

  it('冲突时刷新服务器状态，不保留旧 Key 输入；当前配置不可归档', async () => {
    adminAiApi.update.mockRejectedValue({ response: { status: 409 } })
    const wrapper = render()
    await flushPromises()
    expect(button(wrapper, '归档').attributes('disabled')).toBeDefined()
    await button(wrapper, '编辑').trigger('click')
    await wrapper.get('[aria-label="模型 API Key"]').setValue('sk-conflict-secret')
    await button(wrapper, '保存待启用版本').trigger('click')
    await flushPromises()
    expect(adminAiApi.runtime).toHaveBeenCalledTimes(2)
    await button(wrapper, '编辑').trigger('click')
    expect(wrapper.get('[aria-label="模型 API Key"]').element.value).toBe('')
    wrapper.unmount()
  })

  it('显示未知用量及分币种费用，分页沿用已查询条件', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('#ai-usage-tab').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="usage-call-count"]').text()).toBe('2')
    expect(wrapper.text()).toContain('CNY 0.00045')
    expect(wrapper.text()).toContain('USD 0.001')
    expect(wrapper.text()).toContain('用量未知或不完整')
    expect(wrapper.text()).toContain('未知 / 未配置')
    expect(wrapper.get('table[aria-label="AI 调用明细"]').text()).toContain('未知 / 未知 / 未知')
    expect(wrapper.text()).toContain('1 次费用未知或未配置单价')
    const applied = adminAiApi.calls.mock.calls[0][0]
    expect((Date.parse(applied.to) - Date.parse(applied.from)) / 86400000).toBe(29)
    await wrapper.get('[aria-label="筛选模型"]').setValue('尚未查询的编辑')
    await button(wrapper, '下一页').trigger('click')
    await flushPromises()
    expect(adminAiApi.calls).toHaveBeenLastCalledWith({ ...applied, page: 2 })
    expect(adminAiApi.calls.mock.calls.at(-1)[0].model).toBeUndefined()
    wrapper.unmount()
  })

  it('过长时间范围不发送查询，连接测试和知识草稿使用后端约定的用途筛选', async () => {
    const wrapper = render()
    await flushPromises()
    await wrapper.get('#ai-usage-tab').trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="用量开始日期"]').setValue('2020-01-01')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(adminAiApi.summary).toHaveBeenCalledTimes(1)
    expect(messages.warning).toHaveBeenCalledWith(expect.stringContaining('365 天'))
    await button(wrapper, '近 7 天').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[role="img"]')).toHaveLength(7)
    await wrapper.get('[aria-label="筛选用途"]').setValue('CONNECTION_TEST')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    const params = adminAiApi.summary.mock.calls.at(-1)[0]
    expect(params.purpose).toBe('CONNECTION_TEST')
    expect((Date.parse(params.to) - Date.parse(params.from)) / 86400000).toBe(6)
    await wrapper.get('[aria-label="筛选用途"]').setValue('KNOWLEDGE_DRAFT')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(adminAiApi.summary).toHaveBeenLastCalledWith(expect.objectContaining({ purpose: 'KNOWLEDGE_DRAFT' }))
    expect(adminAiApi.calls).toHaveBeenLastCalledWith(expect.objectContaining({ purpose: 'KNOWLEDGE_DRAFT' }))
    wrapper.unmount()
  })

  it('余额刷新失败保留上次余额并说明是账户余额，拒绝非 HTTPS 控制台链接', async () => {
    adminAiApi.balance.mockResolvedValue({ supported: true, stale: false, checkedAt: '2026-11-03T10:00:00', message: '供应商账户余额', consoleUrl: 'javascript:alert(1)', balances: [{ currency: 'CNY', totalBalance: '50', grantedBalance: '10', toppedUpBalance: '40' }] })
    adminAiApi.refreshBalance.mockRejectedValue(new Error('network'))
    const wrapper = render()
    await flushPromises()
    await button(wrapper, '查看余额').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('多个 Key 的余额不能相加')
    expect(wrapper.find('a[href]').exists()).toBe(false)
    await button(wrapper, '刷新余额').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('当前显示上次查询结果，可能已过期')
    expect(wrapper.text()).toContain('50.00')
    expect(adminAiApi.refreshBalance).toHaveBeenCalledWith(1)
    wrapper.unmount()
  })
})

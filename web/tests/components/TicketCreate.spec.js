import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn(), replace }) }))
vi.mock('../../src/api/ai', () => ({ aiApi: { draft: vi.fn(), feedback: vi.fn() } }))
vi.mock('../../src/api/ticket', () => ({ ticketApi: { create: vi.fn() } }))
import { aiApi } from '../../src/api/ai'
import { ticketApi } from '../../src/api/ticket'
import TicketCreate from '../../src/views/TicketCreateView.vue'

const stubs = {
  PageHeader: { template: '<header />' },
  KnowledgeReferences: true,
  'el-form': { methods: { validate: () => Promise.resolve(true) }, template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option />' },
  'el-button': { template: '<button type="button"><slot /></button>' }
}

describe('智能建单保持人工确认', () => {
  it('生成和采用草稿都不建单，只有确认提交才调用创建接口', async () => {
    aiApi.draft.mockResolvedValue({ title: 'VPN 无法连接', description: '上午开始连接超时', category: 'IT_FAULT', priority: 'MEDIUM',
      missingInformation: ['其他员工是否受到影响？'], steps: ['记录错误提示'], references: [], answerId: 30, conversationId: 'draft-session' })
    aiApi.feedback.mockResolvedValue(null)
    ticketApi.create.mockResolvedValue({ id: 9, ticketNo: 'T-9' })
    const wrapper = mount(TicketCreate, { global: { stubs } })
    await wrapper.find('[aria-label="自然语言描述问题"]').setValue('VPN 连不上')
    await wrapper.find('[data-testid="generate-draft"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('其他员工是否受到影响？')
    expect(ticketApi.create).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="ticket-title"]').element.value).toBe('')
    await wrapper.find('[data-testid="apply-draft"]').trigger('click')
    await flushPromises()
    expect(ticketApi.create).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="ticket-title"]').element.value).toBe('VPN 无法连接')
    expect(aiApi.feedback).toHaveBeenCalledWith(30, { adopted: true })
    await wrapper.find('[data-testid="ticket-title"]').setValue('VPN 仍然连接超时')
    await wrapper.find('[data-testid="ticket-submit"]').trigger('click')
    await flushPromises()
    expect(ticketApi.create).toHaveBeenCalledWith(expect.objectContaining({ title: 'VPN 仍然连接超时', fillDurationSeconds: expect.any(Number) }), expect.any(String))
    expect(replace).toHaveBeenCalledWith({ name: 'ticket-detail', params: { id: 9 } })
    wrapper.unmount()
  })
})

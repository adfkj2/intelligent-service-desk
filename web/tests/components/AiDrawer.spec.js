import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

vi.mock('../../src/api/ai', () => ({ aiApi: { chat: vi.fn(), feedback: vi.fn() } }))
import { aiApi } from '../../src/api/ai'
import AiDrawer from '../../src/components/AiDrawer.vue'

const stubs = {
  'el-drawer': { template: '<section><slot /></section>' },
  'el-input': { props: ['modelValue'], emits: ['update:modelValue'], template: '<textarea :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  'el-button': { template: '<button><slot /></button>' },
  'router-link': { props: ['to'], template: '<a :data-destination="JSON.stringify(to)"><slot /></a>' }
}
function create(ticketId = null) { return mount(AiDrawer, { props: { modelValue: true, ticketId }, global: { stubs } }) }
async function send(wrapper, question) {
  await wrapper.find('textarea').setValue(question)
  await wrapper.findAll('button').find(button => button.text() === '发送').trigger('click')
  await flushPromises()
}

describe('AI 连续对话与上下文隔离', () => {
  it('连续追问带回服务端会话标识，并把引用关联到可打开的知识条目', async () => {
    aiApi.chat.mockResolvedValue({ answer: '先检查 VPN 客户端', source: 'LLM', conversationId: 'session-a', answerId: 12, references: [{ id: 6, title: 'VPN 排查', excerpt: '检查连接配置', environment: 'Windows 11', reviewRequired: true }] })
    const wrapper = create(42)
    await send(wrapper, 'VPN 连不上')
    expect(aiApi.chat).toHaveBeenLastCalledWith({ question: 'VPN 连不上', conversationId: undefined, ticketId: 42 })
    expect(JSON.parse(wrapper.find('a').attributes('data-destination'))).toEqual({ name: 'kb', query: { itemId: 6 } })
    expect(wrapper.text()).toContain('适用环境：Windows 11')
    expect(wrapper.text()).toContain('此条目已到复核日期')
    await send(wrapper, '已经检查了，下一步呢？')
    expect(aiApi.chat).toHaveBeenLastCalledWith({ question: '已经检查了，下一步呢？', conversationId: 'session-a', ticketId: 42 })
    wrapper.unmount()
  })

  it('切换工单时隔离对话，迟到的回答只写回原工单', async () => {
    let finish
    aiApi.chat.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = create(7)
    await send(wrapper, '整理工单七')
    await wrapper.setProps({ ticketId: 8 })
    finish({ answer: '工单七的处理记录', conversationId: 'seven', references: [] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('工单七的处理记录')
    await wrapper.setProps({ ticketId: 7 })
    expect(wrapper.text()).toContain('工单七的处理记录')
    wrapper.unmount()
  })

  it('反馈绑定具体回答，开始新对话后不复用旧会话', async () => {
    aiApi.chat.mockResolvedValue({ answer: '请检查配置', source: 'LLM', conversationId: 'old', answerId: 5, references: [] })
    aiApi.feedback.mockResolvedValue(null)
    const wrapper = create()
    await send(wrapper, '如何检查？')
    await wrapper.findAll('button').find(button => button.text() === '未解决').trigger('click')
    await flushPromises()
    expect(aiApi.feedback).toHaveBeenCalledWith(5, { helpful: false, resolved: false })
    await wrapper.findAll('button').find(button => button.text() === '新对话').trigger('click')
    await send(wrapper, '另一个问题')
    expect(aiApi.chat).toHaveBeenLastCalledWith({ question: '另一个问题', conversationId: undefined, ticketId: undefined })
    wrapper.unmount()
  })
})

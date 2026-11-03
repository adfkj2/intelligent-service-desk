import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

const { confirm } = vi.hoisted(() => ({ confirm: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn(), success: vi.fn() }, ElMessageBox: { confirm } }))
vi.mock('../../src/api/kb', () => ({ kbApi: { drafts: vi.fn(), updateDraft: vi.fn(), publishDraft: vi.fn() } }))
import { kbApi } from '../../src/api/kb'
import KnowledgeDrafts from '../../src/components/KnowledgeDrafts.vue'

const stubs = {
  'el-drawer': { template: '<div><slot /><slot name="footer" /></div>' },
  'el-button': { template: '<button><slot /></button>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-input': { props: ['modelValue'], template: '<textarea :value="modelValue" />' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option />' },
  'el-date-picker': true
}

describe('知识草稿审核', () => {
  it('确认发布后先保存编辑内容，再用返回的新版本发布', async () => {
    const draft = { id: 4, ticketId: 7, title: 'VPN 排查', content: '检查连接配置', status: 'DRAFT', version: 2, source: 'LLM' }
    kbApi.drafts.mockResolvedValue([draft])
    kbApi.updateDraft.mockResolvedValue({ ...draft, categoryId: 3, version: 3 })
    kbApi.publishDraft.mockResolvedValue({ id: 8 })
    confirm.mockResolvedValue('confirm')
    const wrapper = mount(KnowledgeDrafts, { props: { requestedId: 4, categories: [{ id: 3, name: '网络' }] }, global: { stubs } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '审核并发布').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalled()
    expect(kbApi.updateDraft).toHaveBeenCalledWith(4, expect.objectContaining({ version: 2, categoryId: 3 }))
    expect(kbApi.publishDraft).toHaveBeenCalledWith(4, { version: 3, categoryId: 3 })
    expect(wrapper.emitted('published')).toHaveLength(1)
    wrapper.unmount()
  })
})

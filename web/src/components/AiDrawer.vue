<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { aiApi } from '../api/ai'
import KnowledgeReferences from './KnowledgeReferences.vue'

const props = defineProps({ modelValue: Boolean, ticketId: { type: [Number, String], default: null } })
const emit = defineEmits(['update:modelValue'])
const visible = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) })
const sessions = reactive({})
const contextKey = computed(() => props.ticketId ? `ticket:${props.ticketId}` : 'general')
function newSession() { return { messages: [], conversationId: null, question: '', loading: false } }
watch(contextKey, (key) => { if (!sessions[key]) sessions[key] = newSession() }, { immediate: true })
const session = computed(() => sessions[contextKey.value])
const bodyRef = ref(null)
const recommendations = computed(() => props.ticketId
  ? ['总结这张工单的当前进展', '根据现有信息，下一步如何排查？']
  : ['我的工单处理到哪了？', '打印机连不上怎么办？', 'VPN 无法连接如何排查？'])

async function scrollToBottom() {
  await nextTick()
  if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
}
watch(() => [props.modelValue, props.ticketId], scrollToBottom)
function reset() { if (!session.value.loading) sessions[contextKey.value] = newSession() }

async function send(text) {
  const current = session.value
  const content = (text ?? current.question).trim()
  if (!content || current.loading) return
  const ticketId = props.ticketId ? Number(props.ticketId) : undefined
  current.messages.push({ role: 'user', text: content })
  current.question = ''
  current.loading = true
  scrollToBottom()
  try {
    const data = await aiApi.chat({ question: content, conversationId: current.conversationId || undefined, ticketId })
    current.conversationId = data.conversationId
    current.messages.push({ role: 'assistant', text: data.answer, source: data.source,
      references: data.references || [], answerId: data.answerId, feedback: null })
  } catch (error) {
    current.messages.push({ role: 'assistant', text: error?.response?.data?.message || '暂时无法回答。请重试，或到知识库查看排查方法。',
      source: 'ERROR', retry: content })
  } finally {
    current.loading = false
    scrollToBottom()
  }
}

async function feedback(message, helpful) {
  if (!message.answerId || message.feedbackSaving) return
  message.feedbackSaving = true
  try {
    await aiApi.feedback(message.answerId, { helpful, resolved: helpful })
    message.feedback = helpful
  } catch (error) {
    console.error('保存回答反馈失败', error)
  } finally { message.feedbackSaving = false }
}
</script>

<template>
  <el-drawer v-model="visible" direction="rtl" size="min(480px, 100vw)" :with-header="false" class="ai-drawer">
    <div class="flex h-full flex-col">
      <div class="flex items-start justify-between gap-3 border-b border-line px-5 py-4">
        <div>
          <p class="m-0 text-[16px] font-semibold text-ink-900">{{ ticketId ? '当前工单助手' : 'AI 助手' }}</p>
          <p class="m-0 mt-1 text-xs text-ink-600">{{ ticketId ? '结合工单描述与处理记录，可以继续追问' : '查询工单进展，查找有来源的排查方法' }}</p>
        </div>
        <el-button text size="small" :disabled="session.loading" @click="reset">新对话</el-button>
      </div>
      <div ref="bodyRef" class="flex-1 space-y-4 overflow-y-auto bg-canvas px-4 py-4" aria-live="polite">
        <p v-if="!session.messages.length" class="m-0 rounded-lg border border-line bg-white p-4 text-[13px] leading-6 text-ink-600">
          {{ ticketId ? '可以让我整理这张工单的进展，或根据已有信息推荐排查方法。' : '描述你遇到的问题，我会结合你有权查看的工单和知识库提供建议。' }}
        </p>
        <div v-for="(message, index) in session.messages" :key="index" class="flex" :class="message.role === 'user' ? 'justify-end' : 'justify-start'">
          <div class="max-w-[92%] min-w-0">
            <div
              class="whitespace-pre-wrap rounded-lg px-3.5 py-2.5 text-[13px] leading-6"
              :class="message.role === 'user' ? 'bg-brand-600 text-white' : 'border border-line bg-white text-ink-900'">{{ message.text }}</div>
            <template v-if="message.role === 'assistant'">
              <p v-if="message.source === 'FALLBACK'" class="m-0 mt-1.5 text-xs text-ink-600">当前提供知识库检索与基础建议</p>
              <KnowledgeReferences :references="message.references" @navigate="visible = false" />
              <div v-if="message.answerId" class="mt-2 flex gap-2">
                <button type="button" class="isd-text-action" :disabled="message.feedbackSaving" @click="feedback(message, true)">{{ message.feedback === true ? '已反馈：已解决' : '有帮助，已解决' }}</button>
                <button type="button" class="isd-text-action" :disabled="message.feedbackSaving" @click="feedback(message, false)">{{ message.feedback === false ? '已反馈：未解决' : '未解决' }}</button>
              </div>
              <el-button v-if="message.retry" text size="small" :disabled="session.loading" @click="send(message.retry)">重新提问</el-button>
            </template>
          </div>
        </div>
        <p v-if="session.loading" class="text-xs text-ink-600" role="status">正在结合工单和知识库整理回答…</p>
      </div>
      <div class="border-t border-line bg-white px-4 py-3">
        <div v-if="!session.messages.length" class="mb-3 flex flex-wrap gap-2">
          <button v-for="item in recommendations" :key="item" type="button" class="rounded border border-line px-2 py-1 text-xs text-ink-600 hover:border-brand-500" @click="send(item)">{{ item }}</button>
        </div>
        <div class="flex items-end gap-2">
          <el-input v-model="session.question" type="textarea" :rows="3" resize="none" maxlength="1000" aria-label="向 AI 提问" placeholder="输入问题，Enter 发送，Shift+Enter 换行" @keydown.enter.exact.prevent="send()" />
          <el-button type="primary" :loading="session.loading" :disabled="!session.question.trim()" @click="send()">发送</el-button>
        </div>
        <p class="m-0 mt-2 text-[11px] leading-5 text-ink-400">建议供你参考。工单提交和状态变更需要在页面上确认。</p>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
:deep(.el-drawer__body) { padding: 0; }
</style>

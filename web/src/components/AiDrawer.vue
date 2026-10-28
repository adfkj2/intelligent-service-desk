<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { Promotion } from '@element-plus/icons-vue'
import { aiApi } from '../api/ai'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const messages = ref([])
const question = ref('')
const loading = ref(false)
const bodyRef = ref(null)

const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
})

const recommendations = ['我的工单处理到哪了？', '打印机连不上怎么办？', 'VPN 无法连接如何排查？']

watch(visible, (opened) => {
  if (opened && messages.value.length === 0) {
    messages.value.push({
      role: 'assistant',
      text: '你好，我是 IT 服务台助手。我可以查你名下的工单进展，也可以按故障现象检索知识库。',
      source: 'LLM'
    })
  }
})

async function scrollToBottom() {
  await nextTick()
  if (bodyRef.value) {
    bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  }
}

async function send(text) {
  const content = (text ?? question.value).trim()
  if (!content || loading.value) {
    return
  }
  messages.value.push({ role: 'user', text: content })
  question.value = ''
  loading.value = true
  scrollToBottom()

  try {
    const data = await aiApi.chat(content)
    messages.value.push({
      role: 'assistant',
      text: data.answer,
      source: data.source,
      titles: data.relatedTitles || []
    })
  } catch (error) {
    console.error('AI 对话失败', error)
    messages.value.push({
      role: 'assistant',
      text: '这次没连上 AI 服务，请稍后再试，或直接到知识库搜索关键词。',
      source: 'ERROR'
    })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}
</script>

<template>
  <el-drawer v-model="visible" direction="rtl" size="420px" :with-header="false" class="ai-drawer">
    <div class="flex h-full flex-col">
      <div class="flex items-center justify-between border-b border-line px-5 py-4">
        <div>
          <p class="m-0 text-[15px] font-semibold text-ink-900">AI 助手</p>
          <p class="m-0 mt-0.5 text-xs text-ink-600">只读查询 · 基于你的真实工单与知识库</p>
        </div>
        <el-tag size="small" type="info" effect="plain">Beta</el-tag>
      </div>

      <div ref="bodyRef" class="flex-1 space-y-4 overflow-y-auto bg-canvas px-4 py-4">
        <div
          v-for="(message, index) in messages"
          :key="index"
          class="flex"
          :class="message.role === 'user' ? 'justify-end' : 'justify-start'"
        >
          <div class="max-w-[86%]">
            <div
              class="whitespace-pre-wrap rounded-lg px-3.5 py-2.5 text-[13px] leading-6"
              :class="
                message.role === 'user'
                  ? 'rounded-br-sm bg-brand-600 text-white'
                  : 'rounded-bl-sm border border-line bg-white text-ink-900'
              "
            >
              {{ message.text }}
            </div>

            <div v-if="message.role === 'assistant'" class="mt-1.5 flex flex-wrap items-center gap-1.5">
              <el-tag v-if="message.source === 'FALLBACK'" size="small" type="warning" effect="light">
                知识库降级
              </el-tag>
              <el-tag v-else-if="message.source === 'ERROR'" size="small" type="danger" effect="light">
                请求失败
              </el-tag>
              <el-tag v-else size="small" type="success" effect="plain">模型回答</el-tag>
              <span
                v-for="title in message.titles || []"
                :key="title"
                class="rounded bg-brand-50 px-1.5 py-0.5 text-xs text-brand-700"
              >
                {{ title }}
              </span>
            </div>
          </div>
        </div>

        <div v-if="loading" class="flex items-center gap-2 pl-1 text-xs text-ink-600">
          <span class="h-1.5 w-1.5 animate-pulse rounded-full bg-brand-600" />
          正在查询工单与知识库…
        </div>
      </div>

      <div class="border-t border-line bg-white px-4 py-3">
        <div v-if="messages.length <= 1" class="mb-2.5 flex flex-wrap gap-1.5">
          <button
            v-for="item in recommendations"
            :key="item"
            type="button"
            class="cursor-pointer rounded-full border border-line bg-white px-2.5 py-1 text-xs text-ink-600 transition hover:border-brand-500 hover:text-brand-600"
            @click="send(item)"
          >
            {{ item }}
          </button>
        </div>

        <div class="flex items-end gap-2">
          <el-input
            v-model="question"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="500"
            placeholder="描述你的问题，例如：我的工单处理到哪了"
            @keydown.enter.exact.prevent="send()"
          />
          <el-button type="primary" :icon="Promotion" :loading="loading" @click="send()">发送</el-button>
        </div>
        <p class="m-0 mt-2 text-[11px] text-ink-400">AI 只能查询，创建/接单/关闭请到工单页面操作。</p>
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
:deep(.el-drawer__body) {
  padding: 0;
}
</style>

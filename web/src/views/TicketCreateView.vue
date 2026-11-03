<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Sparkles } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import KnowledgeReferences from '../components/KnowledgeReferences.vue'
import { ticketApi } from '../api/ticket'
import { aiApi } from '../api/ai'
import { CATEGORY_OPTIONS, PRIORITY_OPTIONS } from '../constants/ticket'

const router = useRouter()
const formRef = ref(null)
const submitting = ref(false)
const startedAt = Date.now()
const idempotencyKey = ref(window.crypto?.randomUUID?.() || `t${Date.now()}${Math.random().toString(36).slice(2)}`)
const form = reactive({ title: '', description: '', category: 'IT_FAULT', priority: 'MEDIUM' })
const draftInput = ref('')
const supplement = ref('')
const draft = ref(null)
const draftLoading = ref(false)
const draftError = ref('')
const draftApplied = ref(false)
const rules = {
  title: [{ required: true, message: '请填写工单标题', trigger: 'blur' }, { max: 128, message: '标题不能超过 128 个字符', trigger: 'blur' }],
  description: [{ max: 2000, message: '描述不能超过 2000 个字符', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
  priority: [{ required: true, message: '请选择优先级', trigger: 'change' }]
}

async function generateDraft() {
  if (!draftInput.value.trim() || draftLoading.value) return
  if (supplement.value.trim()) {
    draftInput.value = `${draftInput.value}\n补充信息：${supplement.value.trim()}`.slice(0, 2000)
    supplement.value = ''
  }
  draftLoading.value = true
  draftError.value = ''
  try {
    draft.value = await aiApi.draft({ description: draftInput.value.trim(), conversationId: draft.value?.conversationId || undefined })
    draftApplied.value = false
  } catch (error) {
    draftError.value = error?.response?.data?.message || '暂时无法生成草稿，可以重试或直接填写工单。'
  } finally { draftLoading.value = false }
}

async function applyDraft() {
  if (!draft.value) return
  Object.assign(form, { title: draft.value.title, description: draft.value.description, category: draft.value.category, priority: draft.value.priority })
  draftApplied.value = true
  if (draft.value.answerId) {
    try { await aiApi.feedback(draft.value.answerId, { adopted: true }) }
    catch (error) { console.debug('建议采纳反馈暂未保存', error) }
  }
  ElMessage.success('草稿已填入，请核对后提交')
}

async function submit() {
  if (submitting.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const created = await ticketApi.create({ ...form, fillDurationSeconds: Math.floor((Date.now() - startedAt) / 1000) }, idempotencyKey.value)
    ElMessage.success(`工单 ${created.ticketNo} 已创建`)
    router.replace({ name: 'ticket-detail', params: { id: created.id } })
  } catch (error) { console.error('创建工单失败', error) }
  finally { submitting.value = false }
}
</script>

<template>
  <div>
    <el-button text :icon="ArrowLeft" class="mb-4" @click="router.push({ name: 'tickets' })">返回列表</el-button>
    <PageHeader title="新建工单" description="先描述问题，让 AI 帮你整理；也可以直接填写" />
    <div class="grid items-start gap-5 xl:grid-cols-2">
      <section class="isd-card isd-assistance p-5 sm:p-6" aria-label="AI 辅助报障">
        <h2 class="m-0 flex items-center gap-2 text-[16px] font-semibold text-ink-900"><Sparkles :size="18" class="text-brand-600" />先说说遇到了什么</h2>
        <p class="mt-2 text-[13px] leading-6 text-ink-600">不用准备技术术语。写下发生的现象、影响范围和已经尝试过的操作。</p>
        <el-input v-model="draftInput" type="textarea" :rows="5" maxlength="2000" show-word-limit placeholder="例如：VPN 连不上，上午开始提示连接超时，家里网络正常" aria-label="自然语言描述问题" />
        <el-button type="primary" plain class="mt-3" :loading="draftLoading" :disabled="!draftInput.trim()" data-testid="generate-draft" @click="generateDraft">整理工单草稿</el-button>
        <p v-if="draftError" class="text-[13px] text-red-700" role="alert">{{ draftError }}</p>
        <div v-if="draft" class="mt-5 border-t border-line pt-4">
          <template v-if="draft.missingInformation?.length">
            <h3 class="m-0 text-[14px] font-medium text-ink-900">再补充这些信息，定位会更快</h3>
            <ul class="pl-5 text-[13px] leading-7 text-ink-600"><li v-for="item in draft.missingInformation" :key="item">{{ item }}</li></ul>
            <el-input v-model="supplement" type="textarea" :rows="3" maxlength="1000" placeholder="在这里补充，也可以先采用现有草稿" />
            <el-button class="mt-2" :loading="draftLoading" :disabled="!supplement.trim()" @click="generateDraft">补充后重新整理</el-button>
          </template>
          <h3 class="mt-5 mb-2 text-[14px] font-medium text-ink-900">{{ draft.title }}</h3>
          <p class="whitespace-pre-wrap text-[13px] leading-6 text-ink-600">{{ draft.description }}</p>
          <template v-if="draft.steps?.length">
            <p class="mt-4 mb-1 text-[13px] font-medium text-ink-900">提交前可以尝试</p>
            <ol class="pl-5 text-[13px] leading-7 text-ink-600"><li v-for="step in draft.steps" :key="step">{{ step }}</li></ol>
          </template>
          <KnowledgeReferences :references="draft.references" />
          <el-button type="primary" class="mt-4" data-testid="apply-draft" @click="applyDraft">{{ draftApplied ? '重新填入草稿' : '采用草稿并核对' }}</el-button>
          <p class="mb-0 text-xs leading-5 text-ink-400">采用后仍可修改，点击“提交工单”才会创建。</p>
        </div>
      </section>
      <section class="isd-card p-5 sm:p-6" aria-label="工单内容">
        <h2 class="m-0 mb-5 text-[16px] font-semibold text-ink-900">核对工单内容</h2>
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
          <el-form-item label="标题" prop="title"><el-input v-model="form.title" data-testid="ticket-title" maxlength="128" show-word-limit placeholder="一句话说明问题，例如：3 楼打印机无法连接" /></el-form-item>
          <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
            <el-form-item label="分类" prop="category"><el-select v-model="form.category" class="w-full"><el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
            <el-form-item label="优先级" prop="priority"><el-select v-model="form.priority" class="w-full"><el-option v-for="item in PRIORITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          </div>
          <el-form-item label="问题描述" prop="description"><el-input v-model="form.description" type="textarea" :rows="8" maxlength="2000" show-word-limit placeholder="补充发生时间、错误提示、影响范围和已尝试的操作" /></el-form-item>
          <p class="text-xs leading-5 text-ink-600">请按实际影响选择优先级。创建后可以上传截图并继续补充信息。</p>
          <div class="mt-5 flex justify-end gap-2"><el-button @click="router.push({ name: 'tickets' })">取消</el-button><el-button type="primary" data-testid="ticket-submit" :loading="submitting" @click="submit">提交工单</el-button></div>
        </el-form>
      </section>
    </div>
  </div>
</template>

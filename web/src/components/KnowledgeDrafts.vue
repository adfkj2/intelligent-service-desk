<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbApi } from '../api/kb'

const props = defineProps({ categories: { type: Array, default: () => [] }, requestedId: { type: [Number, String], default: null } })
const emit = defineEmits(['published'])
const drafts = ref([])
const loading = ref(false)
const failed = ref(false)
const visible = ref(false)
const saving = ref(false)
const selected = ref(null)
const form = reactive({ categoryId: null, title: '', content: '', tags: '', environment: '', reviewDueAt: '' })
const pending = computed(() => drafts.value.filter(item => item.status === 'DRAFT'))
async function load() {
  loading.value = true
  failed.value = false
  try {
    drafts.value = await kbApi.drafts()
    const requested = drafts.value.find(item => item.status === 'DRAFT' && String(item.id) === String(props.requestedId))
    if (requested && !visible.value) open(requested)
  } catch (error) { failed.value = true; console.error('加载知识草稿失败', error) }
  finally { loading.value = false }
}
onMounted(load)
watch(() => props.requestedId, () => {
  const requested = drafts.value.find(item => item.status === 'DRAFT' && String(item.id) === String(props.requestedId))
  if (requested) open(requested)
})
function open(item) {
  selected.value = item
  Object.assign(form, { categoryId: item.categoryId || props.categories[0]?.id || null, title: item.title, content: item.content,
    tags: item.tags || '', environment: item.environment || '', reviewDueAt: item.reviewDueAt || '' })
  visible.value = true
}
function valid() {
  if (!form.title.trim() || !form.content.trim()) { ElMessage.warning('请填写标题和正文'); return false }
  return true
}
async function persist() {
  const updated = await kbApi.updateDraft(selected.value.id, { ...form, title: form.title.trim(), categoryId: form.categoryId || null,
    reviewDueAt: form.reviewDueAt || null, version: selected.value.version })
  selected.value = updated
  return updated
}
async function save() {
  if (!valid() || saving.value) return
  saving.value = true
  try { await persist(); ElMessage.success('草稿已保存'); await load() }
  catch (error) { console.error('保存知识草稿失败', error) }
  finally { saving.value = false }
}
async function publish() {
  if (!valid() || saving.value) return
  if (!form.categoryId) { ElMessage.warning('请选择发布分类'); return }
  try { await ElMessageBox.confirm('确认已核对适用环境、排查步骤和处理结论？发布后所有员工可以查看。', '审核并发布知识', { confirmButtonText: '确认发布', cancelButtonText: '继续编辑' }) }
  catch { return }
  saving.value = true
  try {
    const updated = await persist()
    await kbApi.publishDraft(updated.id, { categoryId: form.categoryId, version: updated.version })
    visible.value = false
    ElMessage.success('知识已发布')
    emit('published')
    await load()
  } catch (error) { console.error('发布知识失败', error) }
  finally { saving.value = false }
}
</script>

<template>
  <section class="isd-card mb-5 p-5" aria-label="知识审核">
    <div class="flex flex-wrap items-center justify-between gap-3"><div><h2 class="m-0 text-[15px] font-semibold text-ink-900">待审核知识草稿 <span class="ml-1 text-brand-700">{{ pending.length }}</span></h2><p class="mb-0 mt-1 text-xs leading-5 text-ink-600">从已解决工单中整理，审核后才会进入员工可见的知识库。</p></div><el-button text :loading="loading" @click="load">刷新草稿</el-button></div>
    <p v-if="failed" class="text-xs text-red-700" role="alert">暂时无法加载草稿，请点击刷新重试。</p>
    <div v-if="pending.length" class="mt-4 divide-y divide-line"><button v-for="item in pending" :key="item.id" class="flex w-full items-center justify-between gap-3 py-3 text-left" @click="open(item)"><span class="text-[13px] text-ink-900">{{ item.title }}<span class="mt-1 block text-xs text-ink-400">来源工单 #{{ item.ticketId }} · {{ item.environment || '待填写适用环境' }}</span></span><span class="shrink-0 text-xs text-brand-700">审核草稿</span></button></div>
    <p v-else-if="!loading && !failed" class="mb-0 text-xs text-ink-400">在已解决的工单详情中选择“整理为知识草稿”开始沉淀经验。</p>
    <el-drawer v-model="visible" title="审核知识草稿" size="min(680px, 100vw)">
      <p v-if="selected?.source === 'FALLBACK'" class="mt-0 text-xs leading-5 text-ink-600">当前草稿根据工单记录整理，请补齐排查步骤并核对后发布。</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="标题"><el-input v-model="form.title" maxlength="128" show-word-limit /></el-form-item>
        <el-form-item label="发布分类"><el-select v-model="form.categoryId" class="w-full"><el-option v-for="category in categories" :key="category.id" :value="category.id" :label="category.name" /></el-select></el-form-item>
        <el-form-item label="适用环境"><el-input v-model="form.environment" maxlength="255" placeholder="系统版本、设备型号或服务名称" /></el-form-item>
        <el-form-item label="正文"><el-input v-model="form.content" type="textarea" :rows="14" maxlength="20000" show-word-limit /></el-form-item>
        <el-form-item label="标签"><el-input v-model="form.tags" maxlength="255" placeholder="用逗号分隔" /></el-form-item>
        <el-form-item label="下次复核日期"><el-date-picker v-model="form.reviewDueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="安排定期检查是否仍然适用" /></el-form-item>
      </el-form>
      <template #footer><div class="flex justify-end gap-2"><el-button :loading="saving" @click="save">保存草稿</el-button><el-button type="primary" :loading="saving" @click="publish">审核并发布</el-button></div></template>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import KnowledgeDrafts from '../components/KnowledgeDrafts.vue'
import { kbApi } from '../api/kb'
import { useUserStore } from '../stores/user'

const store = useUserStore()
const route = useRoute()

const loading = ref(false)
const categories = ref([])
const items = ref([])
const total = ref(0)
const activeCategory = ref(null)
const query = reactive({ q: '', page: 1, size: 10 })

const editorVisible = ref(false)
const editingId = ref(null)
const saving = ref(false)
const form = reactive({ categoryId: null, title: '', content: '', tags: '' })

const detailVisible = ref(false)
const detail = ref(null)
const feedback = ref({ helpful: 0, unhelpful: 0, myFeedback: null })
const feedbackSaving = ref(false)
const review = reactive({ environment: '', reviewDueAt: '' })
const reviewSaving = ref(false)

const categoryVisible = ref(false)
const categoryForm = reactive({ name: '', description: '' })

const canWrite = computed(() => store.isStaff)

function canManage(row) {
  return store.isAdmin || row.createdBy === store.userId
}

async function loadCategories() {
  try {
    categories.value = await kbApi.categories()
  } catch (error) {
    console.error('加载分类失败', error)
  }
}

async function loadItems() {
  loading.value = true
  try {
    const pageParams = { page: query.page, size: query.size }
    const data = query.q
      ? await kbApi.search({ q: query.q, ...pageParams })
      : await kbApi.items({ ...pageParams, categoryId: activeCategory.value || undefined })
    items.value = data.items
    total.value = data.total
  } catch (error) {
    console.error('加载知识条目失败', error)
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadCategories()
  await loadItems()
  if (route.query.itemId) await openDetail({ id: Number(route.query.itemId) })
})

watch(() => route.query.itemId, (id) => { if (id) openDetail({ id: Number(id) }) })

let debounceTimer = null
watch(
  () => query.q,
  () => {
    clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      query.page = 1
      loadItems()
    }, 300)
  }
)

function pickCategory(id) {
  activeCategory.value = id
  query.q = ''
  query.page = 1
  loadItems()
}

function openCreate() {
  editingId.value = null
  form.categoryId = activeCategory.value || categories.value[0]?.id || null
  form.title = ''
  form.content = ''
  form.tags = ''
  editorVisible.value = true
}

async function openEdit(row) {
  try {
    const data = await kbApi.item(row.id)
    editingId.value = row.id
    form.categoryId = data.categoryId
    form.title = data.title
    form.content = data.content
    form.tags = data.tags || ''
    editorVisible.value = true
  } catch (error) {
    console.error('加载条目失败', error)
  }
}

async function openDetail(row) {
  try {
    detail.value = await kbApi.item(row.id)
    Object.assign(review, { environment: detail.value.environment || '', reviewDueAt: detail.value.reviewDueAt || '' })
    feedback.value = { helpful: 0, unhelpful: 0, myFeedback: null }
    detailVisible.value = true
    feedback.value = await kbApi.feedbackSummary(row.id)
  } catch (error) {
    console.error('加载条目失败', error)
  }
}

async function submitFeedback(helpful) {
  if (feedbackSaving.value) return
  feedbackSaving.value = true
  try { await kbApi.feedback(detail.value.id, helpful); feedback.value = await kbApi.feedbackSummary(detail.value.id) }
  catch (error) { console.error('保存知识反馈失败', error) }
  finally { feedbackSaving.value = false }
}

async function saveReview() {
  if (reviewSaving.value) return
  reviewSaving.value = true
  try {
    detail.value = await kbApi.review(detail.value.id, { environment: review.environment, reviewDueAt: review.reviewDueAt || null })
    ElMessage.success('知识复核已保存')
    await loadItems()
  } catch (error) { console.error('知识复核失败', error) }
  finally { reviewSaving.value = false }
}

function reviewOverdue(item) { return item.reviewDueAt && new Date(item.reviewDueAt).getTime() < Date.now() }

async function saveItem() {
  if (!form.categoryId) {
    ElMessage.warning('请先选择分类')
    return
  }
  if (!form.title.trim() || !form.content.trim()) {
    ElMessage.warning('标题和正文不能为空')
    return
  }
  saving.value = true
  try {
    const payload = {
      categoryId: form.categoryId,
      title: form.title.trim(),
      content: form.content,
      tags: form.tags.trim() || undefined
    }
    if (editingId.value) {
      await kbApi.updateItem(editingId.value, payload)
      ElMessage.success('条目已更新')
    } else {
      await kbApi.createItem(payload)
      ElMessage.success('条目已创建')
    }
    editorVisible.value = false
    loadItems()
  } catch (error) {
    console.error('保存条目失败', error)
  } finally {
    saving.value = false
  }
}

async function removeItem(row) {
  try {
    await ElMessageBox.confirm(`确认删除「${row.title}」？删除后无法恢复。`, '删除确认', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await kbApi.deleteItem(row.id)
    ElMessage.success('已删除')
    loadItems()
  } catch (error) {
    console.error('删除失败', error)
  }
}

async function createCategory() {
  if (!categoryForm.name.trim()) {
    ElMessage.warning('请输入分类名')
    return
  }
  try {
    await kbApi.createCategory({ name: categoryForm.name.trim(), description: categoryForm.description.trim() || undefined })
    ElMessage.success('分类已创建')
    categoryVisible.value = false
    categoryForm.name = ''
    categoryForm.description = ''
    loadCategories()
  } catch (error) {
    console.error('创建分类失败', error)
  }
}
</script>

<template>
  <div>
    <PageHeader title="知识库" description="常见故障的处理经验沉淀，支持关键词检索">
      <template #actions>
        <el-button v-if="store.isAdmin" :icon="Plus" @click="categoryVisible = true">新建分类</el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新建条目</el-button>
      </template>
    </PageHeader>

    <KnowledgeDrafts v-if="canWrite" :categories="categories" :requested-id="route.query.draftId" @published="loadItems" />

    <div class="flex flex-col items-stretch gap-4 lg:flex-row lg:items-start">
      <div class="isd-card w-full shrink-0 py-2 lg:w-[220px]">
        <button
          type="button"
          class="flex w-full cursor-pointer items-center justify-between px-4 py-2.5 text-[13px] transition"
          :class="activeCategory === null ? 'bg-brand-50 font-medium text-brand-700' : 'text-ink-900 hover:bg-canvas'"
          @click="pickCategory(null)"
        >
          全部条目
        </button>
        <button
          v-for="category in categories"
          :key="category.id"
          type="button"
          class="flex w-full cursor-pointer items-center justify-between px-4 py-2.5 text-[13px] transition"
          :class="activeCategory === category.id ? 'bg-brand-50 font-medium text-brand-700' : 'text-ink-900 hover:bg-canvas'"
          @click="pickCategory(category.id)"
        >
          {{ category.name }}
        </button>
        <p v-if="categories.length === 0" class="m-0 px-4 py-3 text-[12px] text-ink-400">
          还没有分类，管理员可先创建。
        </p>
      </div>

      <div class="isd-card min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-3 border-b border-line px-5 py-3.5">
          <el-input v-model="query.q" placeholder="搜索标题、正文或标签" clearable class="w-[320px]">
            <template #prefix><Search :size="14" class="text-ink-400" /></template>
          </el-input>
          <span class="text-[12px] text-ink-600">共 {{ total }} 条</span>
        </div>

        <el-table v-loading="loading" :data="items" class="isd-table" style="width: 100%" empty-text="没有找到条目">
          <el-table-column label="标题" min-width="240">
            <template #default="{ row }">
              <button
                type="button"
                class="cursor-pointer text-left text-[13px] text-ink-900 hover:text-brand-600"
                @click="openDetail(row)"
              >
                {{ row.title }}
              </button>
            </template>
          </el-table-column>
          <el-table-column prop="categoryName" label="分类" width="120" />
          <el-table-column label="标签" min-width="160">
            <template #default="{ row }">
              <span v-if="row.tags" class="text-[12px] text-ink-600">{{ row.tags }}</span>
              <span v-else class="text-[12px] text-ink-400">—</span>
            </template>
          </el-table-column>
          <el-table-column label="复核状态" width="110"><template #default="{ row }"><el-tag v-if="reviewOverdue(row)" type="warning" size="small">待复核</el-tag><span v-else class="text-xs text-ink-400">{{ row.reviewDueAt ? '有效期内' : '未设置' }}</span></template></el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="170" />
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openDetail(row)">查看</el-button>
              <el-button v-if="canManage(row)" link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="canManage(row)" link type="danger" size="small" @click="removeItem(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="flex justify-end border-t border-line px-5 py-3">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            background
            @current-change="loadItems"
            @size-change="() => { query.page = 1; loadItems() }"
          />
        </div>
      </div>
    </div>

    <el-drawer v-model="editorVisible" :title="editingId ? '编辑知识条目' : '新建知识条目'" size="min(560px, 100vw)">
      <el-form label-position="top">
        <el-form-item label="分类">
          <el-select v-model="form.categoryId" class="w-full" placeholder="选择分类">
            <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="例如：打印机无法连接的处理步骤" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" maxlength="255" placeholder="逗号分隔，例如：打印机,驱动" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="14"
            maxlength="20000"
            placeholder="按步骤写清排查与处理过程，便于他人复用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="detailVisible" :title="detail?.title || '条目详情'" width="min(720px, 94vw)">
      <div v-if="detail">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <el-tag size="small" type="info" effect="plain">{{ detail.categoryName }}</el-tag>
          <el-tag v-for="tag in (detail.tags || '').split(',').filter(Boolean)" :key="tag" size="small">
            {{ tag }}
          </el-tag>
        </div>
        <p class="m-0 whitespace-pre-wrap text-[13px] leading-7 text-ink-900">{{ detail.content }}</p>
        <p v-if="detail.environment" class="mt-4 text-xs text-ink-600">适用环境：{{ detail.environment }}</p>
        <p v-if="reviewOverdue(detail)" class="text-xs text-amber-800">此条目已到复核日期，使用时请核对当前环境。</p>
        <router-link v-if="detail.sourceTicketId && store.isStaff" :to="{ name: 'ticket-detail', params: { id: detail.sourceTicketId } }" class="text-xs text-brand-700">查看来源工单 #{{ detail.sourceTicketId }}</router-link>
        <p class="m-0 mt-4 text-[11px] text-ink-400">创建者 用户 {{ detail.createdBy }} · 更新于 {{ detail.updatedAt }}</p>
        <div class="mt-5 border-t border-line pt-4"><p class="text-[13px] font-medium">这篇知识是否有帮助？</p><div class="flex gap-2"><el-button size="small" :type="feedback.myFeedback === true ? 'primary' : 'default'" :loading="feedbackSaving" @click="submitFeedback(true)">有帮助 {{ feedback.helpful }}</el-button><el-button size="small" :type="feedback.myFeedback === false ? 'warning' : 'default'" :loading="feedbackSaving" @click="submitFeedback(false)">需要改进 {{ feedback.unhelpful }}</el-button></div></div>
        <div v-if="canWrite && canManage(detail)" class="mt-5 border-t border-line pt-4"><h3 class="text-[13px] font-medium">复核适用性</h3><el-input v-model="review.environment" maxlength="255" placeholder="更新适用环境" /><div class="mt-3 flex flex-wrap gap-2"><el-date-picker v-model="review.reviewDueAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="下次复核时间" /><el-button :loading="reviewSaving" @click="saveReview">确认复核</el-button></div></div>
      </div>
    </el-dialog>

    <el-dialog v-model="categoryVisible" title="新建分类" width="min(420px, 94vw)">
      <el-form label-position="top">
        <el-form-item label="分类名">
          <el-input v-model="categoryForm.name" maxlength="64" placeholder="例如：账号与权限" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="categoryForm.description" maxlength="255" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryVisible = false">取消</el-button>
        <el-button type="primary" @click="createCategory">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

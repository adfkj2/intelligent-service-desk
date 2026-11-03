<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Plus, Search } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import PriorityDot from '../components/PriorityDot.vue'
import { ticketApi } from '../api/ticket'
import { useUserStore } from '../stores/user'
import { CATEGORY_OPTIONS, STATUS_OPTIONS, PRIORITY_OPTIONS, TICKET_CATEGORY } from '../constants/ticket'
import { deadlineLabel } from '../utils/ticket'

const store = useUserStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const failed = ref(false)
const rows = ref([])
const total = ref(0)
const supports = ref([])
const dates = ref([])
const query = reactive({ page: 1, size: 10, status: '', category: '', keyword: '', priority: '', assigneeId: '', queue: String(route.query.queue || '') })
const queues = computed(() => store.isStaff
  ? [{ value: '', label: '全部工单' }, { value: 'MINE', label: '分配给我' }, { value: 'UNASSIGNED', label: '待接单' }, { value: 'DUE_SOON', label: '即将超时' }, { value: 'OVERDUE', label: '已经超时' }]
  : [{ value: '', label: '我的工单' }, { value: 'WAITING_ME', label: '待我补充' }, { value: 'CONFIRM', label: '待我确认' }])
let latestRequest = 0
async function load() {
  const currentRequest = ++latestRequest
  loading.value = true
  failed.value = false
  try {
    const params = Object.fromEntries(Object.entries(query).filter(([, value]) => value !== ''))
    if (dates.value?.length === 2) {
      params.createdFrom = `${dates.value[0]}T00:00:00`
      params.createdTo = `${dates.value[1]}T23:59:59`
    }
    const data = await ticketApi.list(params)
    if (currentRequest !== latestRequest) return
    rows.value = data.items
    total.value = data.total
  } catch (error) { if (currentRequest === latestRequest) failed.value = true; console.error('加载工单列表失败', error) }
  finally { if (currentRequest === latestRequest) loading.value = false }
}
onMounted(async () => {
  load()
  if (store.isStaff) {
    try { supports.value = await ticketApi.supportOptions() }
    catch (error) { console.error('加载处理人失败', error) }
  }
})
let debounceTimer
watch(() => query.keyword, () => { clearTimeout(debounceTimer); debounceTimer = setTimeout(onFilterChange, 300) })
onBeforeUnmount(() => { clearTimeout(debounceTimer); latestRequest++ })
function onFilterChange() { query.page = 1; load() }
function selectQueue(value) { query.queue = value; onFilterChange() }
function reset() { Object.assign(query, { page: 1, status: '', category: '', keyword: '', priority: '', assigneeId: '', queue: '' }); dates.value = []; load() }
function openTicket(row) { router.push({ name: 'ticket-detail', params: { id: row.id } }) }
</script>

<template>
  <div>
    <PageHeader :title="store.isStaff ? '工单池' : '我的工单'" :description="store.isStaff ? '按负责人、优先级和处理时限安排工作' : '查看进度，及时补充信息并确认处理结果'">
      <template #actions><el-button type="primary" :icon="Plus" @click="router.push({ name: 'ticket-create' })">新建工单</el-button></template>
    </PageHeader>
    <div class="mb-4 flex flex-wrap gap-2"><button v-for="queue in queues" :key="queue.value" class="rounded border px-3 py-2 text-[13px]" :class="query.queue === queue.value ? 'border-brand-600 bg-brand-50 text-brand-700' : 'border-line bg-white text-ink-600'" @click="selectQueue(queue.value)">{{ queue.label }}</button></div>
    <section class="isd-card overflow-hidden">
      <div class="flex flex-wrap items-center gap-3 border-b border-line px-4 py-4 sm:px-5">
        <el-input v-model="query.keyword" placeholder="搜索标题、描述或工单号" clearable class="!w-full sm:!w-[260px]"><template #prefix><Search :size="14" /></template></el-input>
        <el-select v-model="query.status" placeholder="全部状态" clearable class="!w-[140px]" @change="onFilterChange"><el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select>
        <el-select v-model="query.category" placeholder="全部分类" clearable class="!w-[140px]" @change="onFilterChange"><el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select>
        <el-select v-model="query.priority" placeholder="全部优先级" clearable class="!w-[140px]" @change="onFilterChange"><el-option v-for="item in PRIORITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" /></el-select>
        <el-select v-if="store.isStaff" v-model="query.assigneeId" placeholder="全部处理人" clearable class="!w-[160px]" @change="onFilterChange"><el-option v-for="person in supports" :key="person.userId" :label="person.name" :value="person.userId" /></el-select>
        <el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="创建开始日期" end-placeholder="结束日期" class="!max-w-full" @change="onFilterChange" />
        <el-button text @click="reset">重置</el-button><span class="ml-auto text-xs text-ink-600">共 {{ total }} 条</span>
      </div>
      <p v-if="failed" class="px-5 text-[13px] text-red-700" role="alert">未能加载工单。<button class="isd-text-action" @click="load">重新加载</button></p>
      <el-table v-loading="loading" :data="rows" class="isd-table" empty-text="没有符合条件的工单，可调整筛选条件">
        <el-table-column label="工单" min-width="260"><template #default="{ row }"><button class="text-left text-[13px] text-ink-900 hover:text-brand-700" @click="openTicket(row)">{{ row.title }}<span class="mt-1 block text-xs text-ink-400">{{ row.ticketNo }}</span></button></template></el-table-column>
        <el-table-column label="状态" width="116"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
        <el-table-column label="优先级" width="90"><template #default="{ row }"><PriorityDot :priority="row.priority" /></template></el-table-column>
        <el-table-column label="分类" width="110"><template #default="{ row }">{{ TICKET_CATEGORY[row.category] || row.category }}</template></el-table-column>
        <el-table-column label="创建人" width="110"><template #default="{ row }">{{ row.creatorName || `用户 ${row.creatorId}` }}</template></el-table-column>
        <el-table-column label="处理人" width="110"><template #default="{ row }">{{ row.assigneeName || '等待接单' }}</template></el-table-column>
        <el-table-column label="解决时限" min-width="185"><template #default="{ row }"><span class="text-xs" :class="deadlineLabel(row.resolutionDueAt, row.status).startsWith('已超时') ? 'text-red-700' : 'text-ink-600'">{{ deadlineLabel(row.resolutionDueAt, row.status) }}</span></template></el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="170" />
        <el-table-column label="操作" width="85" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openTicket(row)">详情</el-button></template></el-table-column>
      </el-table>
      <div class="overflow-x-auto border-t border-line px-4 py-3"><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" background @current-change="load" @size-change="onFilterChange" /></div>
    </section>
  </div>
</template>

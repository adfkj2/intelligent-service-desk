<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import PriorityDot from '../components/PriorityDot.vue'
import { ticketApi } from '../api/ticket'
import { aiApi } from '../api/ai'
import { useUserStore } from '../stores/user'
import { formatDuration } from '../utils/ticket'
import { TICKET_CATEGORY } from '../constants/ticket'

const store = useUserStore()
const router = useRouter()
const loading = ref(false)
const failed = ref(false)
const overview = ref({ counts: {}, queues: {}, supportWorkloads: [] })
const metrics = ref(null)
const recent = ref([])
const queues = computed(() => store.isStaff ? [
  { value: 'MINE', label: '分配给我', hint: '我负责的在办工单' },
  { value: 'UNASSIGNED', label: '待接单', hint: '等待技术支持接手' },
  { value: 'DUE_SOON', label: '即将超时', hint: '优先安排响应与处理' },
  { value: 'OVERDUE', label: '已经超时', hint: '需要尽快跟进' }
] : [
  { value: 'WAITING_ME', label: '待我补充', hint: '技术支持需要更多信息' },
  { value: 'CONFIRM', label: '待我确认', hint: '确认解决，或反馈未解决' }
])
const rate = (numerator, denominator) => denominator ? `${Math.round(numerator / denominator * 100)}%` : '暂无数据'

async function load() {
  loading.value = true
  failed.value = false
  const results = await Promise.allSettled([ticketApi.overview(), ticketApi.list({ page: 1, size: 6 }), ...(store.isStaff ? [aiApi.metrics()] : [])])
  if (results[0].status === 'fulfilled') overview.value = results[0].value
  else failed.value = true
  if (results[1].status === 'fulfilled') recent.value = results[1].value.items
  if (results[2]?.status === 'fulfilled') metrics.value = results[2].value
  loading.value = false
}
onMounted(load)
function openQueue(queue) { router.push({ name: 'tickets', query: { queue } }) }
</script>

<template>
  <div v-loading="loading">
    <PageHeader title="工作台" :description="store.isStaff ? '先处理需要你跟进的工单' : '查看你的报障进展和待办事项'">
      <template #actions><el-button type="primary" :icon="Plus" @click="router.push({ name: 'ticket-create' })">新建工单</el-button></template>
    </PageHeader>
    <p v-if="failed" role="alert" class="text-[13px] text-red-700">暂时无法加载工作台。<button class="isd-text-action" @click="load">重新加载</button></p>
    <section class="isd-card overflow-hidden" aria-label="待办事项">
      <div class="border-b border-line px-5 py-4"><h2 class="m-0 text-[15px] font-semibold text-ink-900">{{ store.isStaff ? '今天先处理这些' : '需要你跟进' }}</h2></div>
      <div class="grid grid-cols-1 divide-y divide-line sm:grid-cols-2 sm:divide-y-0" :class="store.isStaff ? 'xl:grid-cols-4' : ''">
        <button v-for="queue in queues" :key="queue.value" type="button" class="group flex items-center justify-between gap-4 px-5 py-5 text-left hover:bg-brand-50 focus-visible:bg-brand-50" @click="openQueue(queue.value)">
          <span><span class="block text-[14px] font-medium text-ink-900">{{ queue.label }}</span><span class="mt-1 block text-xs text-ink-600">{{ queue.hint }}</span></span>
          <span class="text-[28px] font-semibold text-brand-700">{{ overview.queues[queue.value] || 0 }}</span>
        </button>
      </div>
    </section>
    <div class="mt-5 grid items-start gap-5 xl:grid-cols-3">
      <section class="isd-card overflow-hidden xl:col-span-2">
        <div class="flex justify-between border-b border-line px-5 py-4"><h2 class="m-0 text-[15px] font-semibold text-ink-900">最近工单</h2><button class="isd-text-action" @click="router.push({ name: 'tickets' })">查看全部</button></div>
        <el-table :data="recent" class="isd-table" empty-text="暂无工单，可提交新的报障" @row-click="row => router.push({ name: 'ticket-detail', params: { id: row.id } })">
          <el-table-column prop="title" label="问题" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="110"><template #default="{ row }"><StatusTag :status="row.status" /></template></el-table-column>
          <el-table-column label="优先级" width="90"><template #default="{ row }"><PriorityDot :priority="row.priority" /></template></el-table-column>
          <el-table-column label="处理人" width="110"><template #default="{ row }">{{ row.assigneeName || '等待接单' }}</template></el-table-column>
        </el-table>
      </section>
      <section class="isd-card p-5">
        <h2 class="m-0 text-[15px] font-semibold text-ink-900">{{ store.isStaff ? '服务效率' : '我的工单概览' }}</h2>
        <dl class="mb-0 space-y-4 text-[13px]">
          <div class="flex justify-between gap-2"><dt class="text-ink-600">首次响应平均用时</dt><dd class="m-0 font-medium">{{ formatDuration(overview.averageResponseSeconds) }}</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-ink-600">解决平均用时</dt><dd class="m-0 font-medium">{{ formatDuration(overview.averageResolutionSeconds) }}</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-ink-600">报障填写平均用时</dt><dd class="m-0 font-medium">{{ formatDuration(overview.averageFillDurationSeconds) }}</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-ink-600">工单重开率</dt><dd class="m-0 font-medium">{{ overview.reopenRate == null ? '暂无数据' : `${Math.round(overview.reopenRate * 100)}%` }}</dd></div>
          <div class="flex justify-between gap-2"><dt class="text-ink-600">补充信息请求次数</dt><dd class="m-0 font-medium">{{ overview.informationRequests || 0 }}</dd></div>
        </dl>
        <template v-if="metrics">
          <h3 class="mt-6 border-t border-line pt-4 text-[13px] font-medium text-ink-900">AI 使用反馈</h3>
          <p class="text-xs leading-6 text-ink-600">收到 {{ metrics.feedbackCount }} 条反馈；有帮助 {{ rate(metrics.helpfulCount, metrics.helpfulFeedbackCount) }}；反馈已解决 {{ metrics.resolvedCount }} 次；建议采纳 {{ rate(metrics.suggestionAdoptedCount, metrics.suggestionCount) }}（{{ metrics.suggestionAdoptedCount || 0 }}/{{ metrics.suggestionCount || 0 }}）。</p>
        </template>
      </section>
    </div>
    <section v-if="store.isAdmin" class="isd-card mt-5 overflow-hidden">
      <h2 class="m-0 border-b border-line px-5 py-4 text-[15px] font-semibold text-ink-900">人员负荷与技能</h2>
      <el-table :data="overview.supportWorkloads || []" class="isd-table" empty-text="暂无技术支持人员">
        <el-table-column prop="name" label="技术支持" min-width="140" />
        <el-table-column prop="activeTickets" label="在办工单" width="120" />
        <el-table-column label="擅长分类" min-width="200"><template #default="{ row }">{{ row.skills?.map(skill => TICKET_CATEGORY[skill] || skill).join('、') || '尚未设置' }}</template></el-table-column>
      </el-table>
    </section>
  </div>
</template>

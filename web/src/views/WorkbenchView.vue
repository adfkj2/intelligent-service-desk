<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Plus } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import PriorityDot from '../components/PriorityDot.vue'
import { ticketApi } from '../api/ticket'
import { useUserStore } from '../stores/user'
import { STATUS_OPTIONS, TICKET_STATUS } from '../constants/ticket'

const store = useUserStore()
const router = useRouter()

const loading = ref(false)
const counts = ref({ OPEN: 0, IN_PROGRESS: 0, RESOLVED: 0, CLOSED: 0 })
const recent = ref([])

// 统计复用列表接口的 total：/stats/{status} 统计的是全库数量，会把公司工单总量暴露给员工
async function load() {
  loading.value = true
  try {
    const [statusCounts, list] = await Promise.all([
      Promise.all(
        STATUS_OPTIONS.map((option) =>
          ticketApi
            .list({ status: option.value, page: 1, size: 1 })
            .then((data) => [option.value, data.total])
        )
      ),
      ticketApi.list({ page: 1, size: 6 })
    ])
    counts.value = Object.fromEntries(statusCounts)
    recent.value = list.items
  } catch (error) {
    console.error('加载工作台失败', error)
  } finally {
    loading.value = false
  }
}

onMounted(load)

const cards = computed(() =>
  STATUS_OPTIONS.map((option) => ({
    ...option,
    color: TICKET_STATUS[option.value].color,
    count: counts.value[option.value] || 0
  }))
)

const scopeHint = computed(() =>
  store.isStaff ? '全公司在办工单概览' : '仅统计你本人提交的工单'
)

function openTicket(row) {
  router.push({ name: 'ticket-detail', params: { id: row.id } })
}
</script>

<template>
  <div>
    <PageHeader title="工作台" :description="scopeHint">
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="router.push({ name: 'ticket-create' })">
          新建工单
        </el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="grid grid-cols-2 gap-4 xl:grid-cols-4">
      <div v-for="card in cards" :key="card.value" class="isd-card overflow-hidden">
        <div class="h-[3px] w-full" :style="{ backgroundColor: card.color }" />
        <div class="px-5 py-4">
          <p class="m-0 text-[12px] text-ink-600">{{ card.label }}</p>
          <p class="m-0 mt-2 text-[28px] font-semibold leading-none text-ink-900">{{ card.count }}</p>
        </div>
      </div>
    </div>

    <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
      <div class="isd-card xl:col-span-2">
        <div class="flex items-center justify-between border-b border-line px-5 py-3.5">
          <p class="m-0 text-[14px] font-medium text-ink-900">最近工单</p>
          <button
            type="button"
            class="flex cursor-pointer items-center gap-0.5 text-[12px] text-brand-600 hover:text-brand-700"
            @click="router.push({ name: 'tickets' })"
          >
            查看全部 <ArrowRight :size="13" />
          </button>
        </div>

        <el-table
          :data="recent"
          class="isd-table"
          style="width: 100%"
          :show-header="true"
          empty-text="暂无工单"
          @row-click="openTicket"
        >
          <el-table-column prop="ticketNo" label="工单号" width="150" />
          <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="96">
            <template #default="{ row }">
              <StatusTag :status="row.status" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="90">
            <template #default="{ row }">
              <PriorityDot :priority="row.priority" />
            </template>
          </el-table-column>
          <el-table-column prop="updatedAt" label="更新时间" width="160" />
        </el-table>
      </div>

      <div class="space-y-4">
        <div class="isd-card p-5">
          <p class="m-0 text-[14px] font-medium text-ink-900">快捷操作</p>
          <div class="mt-3 space-y-2">
            <button
              type="button"
              class="flex w-full cursor-pointer items-center justify-between rounded-md border border-line px-3 py-2.5 text-[13px] text-ink-900 transition hover:border-brand-500 hover:text-brand-600"
              @click="router.push({ name: 'ticket-create' })"
            >
              提交新的故障工单
              <ArrowRight :size="14" />
            </button>
            <button
              type="button"
              class="flex w-full cursor-pointer items-center justify-between rounded-md border border-line px-3 py-2.5 text-[13px] text-ink-900 transition hover:border-brand-500 hover:text-brand-600"
              @click="router.push({ name: 'tickets' })"
            >
              {{ store.isStaff ? '进入工单池处理' : '查看我的工单' }}
              <ArrowRight :size="14" />
            </button>
            <button
              type="button"
              class="flex w-full cursor-pointer items-center justify-between rounded-md border border-line px-3 py-2.5 text-[13px] text-ink-900 transition hover:border-brand-500 hover:text-brand-600"
              @click="router.push({ name: 'kb' })"
            >
              检索知识库
              <ArrowRight :size="14" />
            </button>
          </div>
        </div>

        <div class="isd-card bg-gradient-to-br from-[#111827] to-[#1f2937] p-5">
          <p class="m-0 text-[13px] font-medium text-white">遇到问题先问 AI</p>
          <p class="m-0 mt-1.5 text-[12px] leading-5 text-gray-400">
            右下角的助手能查你名下工单的处理进展，也能按故障现象检索知识库条目。
          </p>
          <p class="m-0 mt-3 text-[11px] text-gray-500">AI 只有查询权限，不会替你创建或关闭工单。</p>
        </div>
      </div>
    </div>
  </div>
</template>

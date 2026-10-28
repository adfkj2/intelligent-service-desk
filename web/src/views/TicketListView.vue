<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search } from 'lucide-vue-next'
import PageHeader from '../components/PageHeader.vue'
import StatusTag from '../components/StatusTag.vue'
import PriorityDot from '../components/PriorityDot.vue'
import { ticketApi } from '../api/ticket'
import { adminApi } from '../api/admin'
import { useUserStore } from '../stores/user'
import { CATEGORY_OPTIONS, STATUS_OPTIONS, TICKET_CATEGORY } from '../constants/ticket'

const store = useUserStore()
const router = useRouter()

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, status: '', category: '', keyword: '' })

const assignVisible = ref(false)
const assignTarget = ref(null)
const assignTicket = ref(null)
const supportOptions = ref([])

async function load() {
  loading.value = true
  try {
    const params = { page: query.page, size: query.size }
    if (query.status) params.status = query.status
    if (query.category) params.category = query.category
    if (query.keyword) params.keyword = query.keyword
    const data = await ticketApi.list(params)
    rows.value = data.items
    total.value = data.total
  } catch (error) {
    console.error('加载工单列表失败', error)
  } finally {
    loading.value = false
  }
}

onMounted(load)

let debounceTimer = null
watch(
  () => query.keyword,
  () => {
    clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      query.page = 1
      load()
    }, 300)
  }
)

function resetQuery() {
  query.status = ''
  query.category = ''
  query.keyword = ''
  query.page = 1
  load()
}

function onFilterChange() {
  query.page = 1
  load()
}

async function claim(row) {
  try {
    await ElMessageBox.confirm(`确认接单「${row.title}」？接单后由你负责处理。`, '接单确认', {
      confirmButtonText: '确认接单',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await ticketApi.claim(row.id)
    ElMessage.success('接单成功')
    load()
  } catch (error) {
    // 409（被他人抢先）已由拦截器提示，这里只需刷新列表看到最新状态
    console.error('接单失败', error)
    load()
  }
}

async function openAssign(row) {
  assignTicket.value = row
  assignTarget.value = null
  assignVisible.value = true
  if (supportOptions.value.length === 0) {
    try {
      const users = await adminApi.users()
      supportOptions.value = users.filter((item) => item.role === 'SUPPORT' && item.status === 1)
    } catch (error) {
      console.error('加载技术支持列表失败', error)
    }
  }
}

async function submitAssign() {
  if (!assignTarget.value) {
    ElMessage.warning('请选择技术支持')
    return
  }
  try {
    await ticketApi.assign(assignTicket.value.id, assignTarget.value)
    ElMessage.success('指派成功')
    assignVisible.value = false
    load()
  } catch (error) {
    console.error('指派失败', error)
  }
}
</script>

<template>
  <div>
    <PageHeader
      :title="store.isStaff ? '工单池' : '我的工单'"
      :description="store.isStaff ? '全部员工提交的工单，可接单或指派' : '仅展示你提交的工单'"
    >
      <template #actions>
        <el-button type="primary" :icon="Plus" @click="router.push({ name: 'ticket-create' })">
          新建工单
        </el-button>
      </template>
    </PageHeader>

    <div class="isd-card">
      <div class="flex flex-wrap items-center gap-3 border-b border-line px-5 py-3.5">
        <el-select v-model="query.status" placeholder="全部状态" clearable class="w-[140px]" @change="onFilterChange">
          <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-select
          v-model="query.category"
          placeholder="全部分类"
          clearable
          class="w-[140px]"
          @change="onFilterChange"
        >
          <el-option v-for="item in CATEGORY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>

        <el-input v-model="query.keyword" placeholder="搜索标题或描述" clearable class="w-[300px]">
          <template #prefix><Search :size="14" class="text-ink-400" /></template>
        </el-input>

        <el-button text @click="resetQuery">重置</el-button>

        <span class="ml-auto text-[12px] text-ink-600">共 {{ total }} 条</span>
      </div>

      <el-table v-loading="loading" :data="rows" class="isd-table" style="width: 100%" empty-text="没有符合条件的工单">
        <el-table-column prop="ticketNo" label="工单号" width="150" />
        <el-table-column label="标题" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <button
              type="button"
              class="cursor-pointer text-left text-[13px] text-ink-900 hover:text-brand-600"
              @click="router.push({ name: 'ticket-detail', params: { id: row.id } })"
            >
              {{ row.title }}
            </button>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }"><StatusTag :status="row.status" size="small" /></template>
        </el-table-column>
        <el-table-column label="优先级" width="88">
          <template #default="{ row }"><PriorityDot :priority="row.priority" /></template>
        </el-table-column>
        <el-table-column label="分类" width="110">
          <template #default="{ row }">
            <span class="text-[13px] text-ink-600">{{ TICKET_CATEGORY[row.category] || row.category }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="creatorId" label="创建人" width="90" />
        <el-table-column label="处理人" width="90">
          <template #default="{ row }">
            <span class="text-[13px]" :class="row.assigneeId ? 'text-ink-900' : 'text-ink-400'">
              {{ row.assigneeId || '未分配' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="160" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <div class="flex items-center gap-1">
              <el-button link type="primary" size="small" @click="router.push({ name: 'ticket-detail', params: { id: row.id } })">
                详情
              </el-button>
              <el-button
                v-if="store.isSupport && row.status === 'OPEN'"
                link
                type="warning"
                size="small"
                @click="claim(row)"
              >
                接单
              </el-button>
              <el-button v-if="store.isAdmin && row.status === 'OPEN'" link type="danger" size="small" @click="openAssign(row)">
                指派
              </el-button>
            </div>
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
          @current-change="load"
          @size-change="onFilterChange"
        />
      </div>
    </div>

    <el-dialog v-model="assignVisible" title="指派工单" width="420px">
      <p class="m-0 mb-3 text-[13px] text-ink-600">
        工单：{{ assignTicket?.ticketNo }} · {{ assignTicket?.title }}
      </p>
      <el-select v-model="assignTarget" placeholder="选择技术支持" class="w-full">
        <el-option
          v-for="user in supportOptions"
          :key="user.id"
          :label="`${user.fullName || user.username}（ID ${user.id}）`"
          :value="user.id"
        />
      </el-select>
      <p v-if="supportOptions.length === 0" class="m-0 mt-2 text-[12px] text-ink-400">
        暂无可用的技术支持账号，请先到用户管理启用。
      </p>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAssign">确认指派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

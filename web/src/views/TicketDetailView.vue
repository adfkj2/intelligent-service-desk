<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from 'lucide-vue-next'
import StatusTag from '../components/StatusTag.vue'
import PriorityDot from '../components/PriorityDot.vue'
import { ticketApi } from '../api/ticket'
import { adminApi } from '../api/admin'
import { useUserStore } from '../stores/user'
import { ACTION_LABEL, TICKET_CATEGORY } from '../constants/ticket'

const route = useRoute()
const router = useRouter()
const store = useUserStore()

const id = route.params.id
const loading = ref(false)
const ticket = ref(null)
const operations = ref([])

const actionVisible = ref(false)
const actionType = ref('RESOLVE')
const comment = ref('')
const submitting = ref(false)

const assignVisible = ref(false)
const assignTarget = ref(null)
const supportOptions = ref([])

const canClaim = computed(() => store.isSupport && ticket.value?.status === 'OPEN')
const canAssign = computed(() => store.isAdmin && ticket.value?.status === 'OPEN')
const canResolve = computed(
  () =>
    ticket.value?.status === 'IN_PROGRESS' &&
    (store.isAdmin || ticket.value?.assigneeId === store.userId)
)
const canClose = computed(
  () => ticket.value?.status === 'RESOLVED' && (store.isAdmin || ticket.value?.creatorId === store.userId)
)

async function load() {
  loading.value = true
  try {
    const [detail, logs] = await Promise.all([ticketApi.detail(id), ticketApi.operations(id)])
    ticket.value = detail
    operations.value = logs
  } catch (error) {
    console.error('加载工单详情失败', error)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function claim() {
  try {
    await ElMessageBox.confirm('确认接单？接单后由你负责处理该工单。', '接单确认', {
      confirmButtonText: '确认接单',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    await ticketApi.claim(id)
    ElMessage.success('接单成功')
    load()
  } catch (error) {
    console.error('接单失败', error)
    load()
  }
}

function openAction(type) {
  actionType.value = type
  comment.value = ''
  actionVisible.value = true
}

async function submitAction() {
  submitting.value = true
  try {
    await ticketApi.changeStatus(id, { action: actionType.value, comment: comment.value || undefined })
    ElMessage.success(actionType.value === 'RESOLVE' ? '已标记解决' : '工单已关闭')
    actionVisible.value = false
    load()
  } catch (error) {
    console.error('状态变更失败', error)
  } finally {
    submitting.value = false
  }
}

async function openAssign() {
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
    await ticketApi.assign(id, assignTarget.value)
    ElMessage.success('指派成功')
    assignVisible.value = false
    load()
  } catch (error) {
    console.error('指派失败', error)
  }
}
</script>

<template>
  <div v-loading="loading">
    <div class="mb-5 flex items-center gap-3">
      <el-button text :icon="ArrowLeft" @click="router.push({ name: 'tickets' })">返回列表</el-button>
      <span class="text-[13px] text-ink-400">/</span>
      <span class="text-[13px] text-ink-600">{{ ticket?.ticketNo || '工单详情' }}</span>
    </div>

    <div v-if="ticket" class="grid grid-cols-1 gap-4 xl:grid-cols-3">
      <div class="space-y-4 xl:col-span-2">
        <div class="isd-card p-6">
          <div class="flex items-start justify-between gap-4">
            <div>
              <h1 class="m-0 text-[20px] font-semibold leading-7 text-ink-900">{{ ticket.title }}</h1>
              <p class="m-0 mt-1 text-[12px] text-ink-400">{{ ticket.ticketNo }}</p>
            </div>
            <StatusTag :status="ticket.status" />
          </div>

          <el-descriptions :column="2" class="mt-5" border size="small">
            <el-descriptions-item label="分类">
              {{ TICKET_CATEGORY[ticket.category] || ticket.category }}
            </el-descriptions-item>
            <el-descriptions-item label="优先级"><PriorityDot :priority="ticket.priority" /></el-descriptions-item>
            <el-descriptions-item label="创建人">用户 {{ ticket.creatorId }}</el-descriptions-item>
            <el-descriptions-item label="处理人">
              {{ ticket.assigneeId ? `用户 ${ticket.assigneeId}` : '未分配' }}
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ ticket.createdAt }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ ticket.updatedAt }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="isd-card p-6">
          <p class="m-0 text-[14px] font-medium text-ink-900">问题描述</p>
          <p class="mt-3 mb-0 whitespace-pre-wrap text-[13px] leading-7 text-ink-600">
            {{ ticket.description || '（提交时未填写描述）' }}
          </p>
        </div>
      </div>

      <div class="space-y-4">
        <div class="isd-card p-5">
          <p class="m-0 text-[14px] font-medium text-ink-900">可执行操作</p>

          <div class="mt-3 space-y-2">
            <el-button v-if="canClaim" type="primary" class="w-full" @click="claim">接单处理</el-button>
            <el-button v-if="canAssign" type="danger" plain class="w-full" @click="openAssign">指派给技术支持</el-button>
            <el-button v-if="canResolve" type="success" class="w-full" @click="openAction('RESOLVE')">
              标记为已解决
            </el-button>
            <el-button v-if="canClose" type="primary" class="w-full" @click="openAction('CLOSE')">
              确认关闭工单
            </el-button>

            <p
              v-if="!canClaim && !canAssign && !canResolve && !canClose"
              class="m-0 rounded-md bg-canvas px-3 py-2.5 text-[12px] leading-5 text-ink-600"
            >
              当前状态下你无需执行操作。工单流转由处理人或创建者推进。
            </p>
          </div>

          <p class="m-0 mt-4 text-[11px] leading-5 text-ink-400">
            规则：员工只能关闭自己提交的工单；标记解决由当前处理人操作；管理员可代为处理。
          </p>
        </div>

        <div class="isd-card p-5">
          <p class="m-0 text-[14px] font-medium text-ink-900">处理时间线</p>
          <el-timeline class="mt-4 pl-1">
            <el-timeline-item
              v-for="log in operations"
              :key="log.id"
              :timestamp="log.createdAt"
              placement="top"
              size="normal"
            >
              <p class="m-0 text-[13px] font-medium text-ink-900">
                {{ ACTION_LABEL[log.action] || log.action }}
                <span class="ml-1 text-[11px] font-normal text-ink-400">用户 {{ log.operatorId }}</span>
              </p>
              <p v-if="log.fromStatus || log.toStatus" class="m-0 mt-1 text-[11px] text-ink-600">
                {{ log.fromStatus || '—' }} → {{ log.toStatus || '—' }}
              </p>
              <p v-if="log.comment" class="m-0 mt-1 text-[12px] text-ink-600">{{ log.comment }}</p>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </div>

    <el-dialog
      v-model="actionVisible"
      :title="actionType === 'RESOLVE' ? '标记为已解决' : '确认关闭工单'"
      width="440px"
    >
      <p class="m-0 mb-3 text-[13px] text-ink-600">
        {{ actionType === 'RESOLVE' ? '填写处理说明，便于创建者确认。' : '确认问题已解决，关闭后将无法再流转。' }}
      </p>
      <el-input
        v-model="comment"
        type="textarea"
        :rows="3"
        maxlength="500"
        show-word-limit
        :placeholder="actionType === 'RESOLVE' ? '例如：已重启打印服务并重新添加打印机' : '例如：问题已解决，感谢'"
      />
      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAction">确认</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="assignVisible" title="指派工单" width="420px">
      <el-select v-model="assignTarget" placeholder="选择技术支持" class="w-full">
        <el-option
          v-for="user in supportOptions"
          :key="user.id"
          :label="`${user.fullName || user.username}（ID ${user.id}）`"
          :value="user.id"
        />
      </el-select>
      <template #footer>
        <el-button @click="assignVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAssign">确认指派</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import ResetPasswordDialog from '../components/ResetPasswordDialog.vue'
import { adminApi } from '../api/admin'
import { useUserStore } from '../stores/user'
import { ROLE_LABEL, ROLE_TAG_TYPE } from '../constants/ticket'

const store = useUserStore()
const loading = ref(false)
const users = ref([])

const roleDialogVisible = ref(false)
const roleTarget = ref(null)
const roleValue = ref('')
const roleSubmitting = ref(false)

const resetVisible = ref(false)
const resetResult = ref(null)

/** 角色说明放在选项里，管理员不用记 EMPLOYEE/SUPPORT/ADMIN 各能做什么 */
const ROLE_OPTIONS = [
  { value: 'EMPLOYEE', label: '员工', hint: '提交报障工单，只能查看自己的工单' },
  { value: 'SUPPORT', label: '技术支持', hint: '查看工单池、接单处理、维护知识库' },
  { value: 'ADMIN', label: '管理员', hint: '指派工单、调整角色、管理用户与知识库分类' }
]

/** 409 = 别人已经改过这一行，本地数据已过期，必须重新拉取 */
function isConflict(error) {
  return error?.response?.status === 409
}

async function load() {
  loading.value = true
  try {
    users.value = await adminApi.users()
  } catch (error) {
    console.error('加载用户列表失败', error)
  } finally {
    loading.value = false
  }
}

onMounted(load)

async function toggleStatus(row) {
  const nextStatus = row.status === 1 ? 0 : 1
  const actionText = nextStatus === 0 ? '禁用' : '启用'

  try {
    await ElMessageBox.confirm(`确认${actionText}账号「${row.username}」？`, `${actionText}确认`, {
      confirmButtonText: `确认${actionText}`,
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }

  try {
    const updated = await adminApi.updateStatus(row.id, nextStatus)
    row.status = updated.status
    ElMessage.success(`已${actionText}`)
  } catch (error) {
    // 例如禁用自己、或另一个管理员刚改过这一行（409），刷新后以服务端为准
    console.error('更新用户状态失败', error)
    if (isConflict(error)) {
      load()
    }
  }
}

function openRole(row) {
  roleTarget.value = row
  roleValue.value = row.role
  roleDialogVisible.value = true
}

async function resetPassword(row) {
  try {
    await ElMessageBox.confirm(
      `将为「${row.username}」生成一次性临时密码。对方当前登录会话会立即失效，首次登录必须改密。`,
      '重置密码',
      {
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  try {
    resetResult.value = await adminApi.resetPassword(row.id)
    resetVisible.value = true
  } catch (error) {
    // 409（重置自己）等错误由拦截器提示
    console.error('重置密码失败', error)
  }
}

async function submitRole() {
  if (!roleTarget.value) {
    return
  }
  if (roleValue.value === roleTarget.value.role) {
    ElMessage.info('角色未发生变化')
    return
  }
  roleSubmitting.value = true
  try {
    const updated = await adminApi.updateRole(roleTarget.value.id, roleValue.value)
    // 用后端返回值刷新行，避免前端猜字段
    roleTarget.value.role = updated.role
    roleDialogVisible.value = false
    ElMessage.success(
      `已将「${updated.username}」调整为${ROLE_LABEL[updated.role] || updated.role}，对方需重新登录后生效`
    )
  } catch (error) {
    // 400 非法角色 / 403 非管理员：保留弹窗便于修正
    // 409 说明这一行已被其他管理员改过：关闭弹窗并刷新，避免拿过期数据继续操作
    console.error('调整角色失败', error)
    if (isConflict(error)) {
      roleDialogVisible.value = false
      load()
    }
  } finally {
    roleSubmitting.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader
      title="用户管理"
      description="注册账号默认是员工；在此可启用/禁用账号，或调整为技术支持、管理员。"
    />

    <div class="isd-card">
      <el-table v-loading="loading" :data="users" class="isd-table" style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="用户名" min-width="160" />
        <el-table-column label="显示名" min-width="160">
          <template #default="{ row }">
            <span class="text-[13px] text-ink-900">{{ row.fullName || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="200">
          <template #default="{ row }">
            <div class="flex items-center gap-2">
              <el-tag :type="ROLE_TAG_TYPE[row.role] || 'info'" size="small" effect="light">
                {{ ROLE_LABEL[row.role] || row.role }}
              </el-tag>
              <el-button
                v-if="row.id !== store.userId"
                link
                type="primary"
                size="small"
                @click="openRole(row)"
              >
                调整
              </el-button>
              <span v-else class="text-[11px] text-ink-400">当前账号</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-button
              v-if="row.id !== store.userId"
              link
              type="primary"
              size="small"
              @click="resetPassword(row)"
            >
              重置密码
            </el-button>
            <span v-else class="text-[11px] text-ink-400">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <div class="flex items-center gap-2">
              <el-switch
                :model-value="row.status === 1"
                :disabled="row.id === store.userId"
                @change="toggleStatus(row)"
              />
              <span class="text-[12px]" :class="row.status === 1 ? 'text-emerald-600' : 'text-ink-400'">
                {{ row.status === 1 ? '启用' : '已禁用' }}
              </span>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <ResetPasswordDialog v-model="resetVisible" :payload="resetResult" />

      <div class="border-t border-line px-5 py-3 text-[12px] leading-5 text-ink-400">
        共 {{ users.length }} 个账号。为避免把自己锁在系统外，当前登录账号的状态与角色不可自行修改。
      </div>
    </div>

    <el-dialog v-model="roleDialogVisible" title="调整用户角色" width="460px">
      <p class="m-0 text-[13px] text-ink-600">
        账号
        <span class="font-medium text-ink-900">{{ roleTarget?.username }}</span>
        当前角色
        <el-tag :type="ROLE_TAG_TYPE[roleTarget?.role] || 'info'" size="small" effect="plain">
          {{ ROLE_LABEL[roleTarget?.role] || roleTarget?.role }}
        </el-tag>
      </p>

      <el-radio-group v-model="roleValue" class="mt-4 flex flex-col gap-2">
        <el-radio
          v-for="option in ROLE_OPTIONS"
          :key="option.value"
          :value="option.value"
          border
          class="!mr-0 h-auto w-full py-2"
        >
          <span class="flex flex-col items-start gap-0.5">
            <span class="text-[13px] font-medium text-ink-900">{{ option.label }}</span>
            <span class="text-[11px] font-normal text-ink-400">{{ option.hint }}</span>
          </span>
        </el-radio>
      </el-radio-group>

      <p class="m-0 mt-4 rounded-md bg-canvas px-3 py-2 text-[12px] leading-5 text-ink-600">
        角色会立即写入数据库，但对方当前会话仍按旧角色运行，需要重新登录才会生效。
      </p>

      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="roleSubmitting"
          :disabled="roleValue === roleTarget?.role"
          @click="submitRole"
        >
          确认调整
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
